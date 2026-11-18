package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.dto.BookingResponse;
import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import com.squirrel.tickting.domain.booking.infra.repository.BookingRepository;
import com.squirrel.tickting.domain.booking.infra.repository.BookingSeatRepository;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class BookingService {

	private final BookingRepository bookingRepository;
	private final BookingSeatRepository bookingSeatRepository;
	private final ObjectMapper objectMapper;

	/**
	 * 한 사람이 한 공연에서 이미 가진 활성 좌석 수. 결제 대기 좌석도 포함한다.
	 * 분산락(사용자·공연) 안에서 불러야 동시에 들어온 두 요청이 같은 값을 보고 함께 통과하지 않는다.
	 */
	@Transactional(readOnly = true)
	public long countActiveSeats(Long userId, Long performanceId) {
		return bookingSeatRepository.countActiveByUserAndPerformance(userId, performanceId);
	}

	/**
	 * 결제할 수 있는 내 예매를 읽는다. 없거나 남의 예매면 같은 404, 결제 대기(PENDING)가 아니거나 결제 제한 시각이 지났으면 409 다.
	 * 스케줄러가 아직 정리하지 않았더라도 시각이 지난 예매는 여기서 막는다. 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public Booking getPayable(Long userId, Long bookingId, LocalDateTime now) {
		Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
			.orElseThrow(() -> new BusinessException(BookingExceptionCode.BOOKING_NOT_FOUND));
		if (booking.getStatus() != BookingStatus.PENDING || !booking.getExpiresAt().isAfter(now)) {
			throw new BusinessException(BookingExceptionCode.BOOKING_NOT_PAYABLE);
		}
		return booking;
	}

	/** 내 예매를 찾는다. 없거나 남의 예매면 비어 있다. 호출한 쪽이 "없는 결제" 같은 자기 도메인의 404 로 바꿔 답할 때 쓴다. */
	@Transactional(readOnly = true)
	public Optional<Booking> findMine(Long userId, Long bookingId) {
		return bookingRepository.findByIdAndUserId(bookingId, userId);
	}

	/** 예매의 활성 좌석에 걸린 판매 좌석 번호. 승인을 시작할 때 점유를 늘리거나 승인 뒤 판매 완료로 바꿀 좌석을 정하는 데 쓴다. */
	@Transactional(readOnly = true)
	public List<Long> findActiveScheduleSeatIds(Long bookingId) {
		return bookingSeatRepository.findActiveScheduleSeatIds(List.of(bookingId));
	}

	/** 결제 승인을 시작할 때 결제 제한 시각을 늘린다. 읽어 온 예매가 같은 트랜잭션에 있어서 커밋 때 저장된다. */
	@Transactional
	public void extendExpiry(Booking booking, LocalDateTime expiresAt) {
		booking.extendExpiry(expiresAt);
	}

	/**
	 * 결제 완료 처리. 결제 대기였던 예매만 PAID 로 바꾼다. 이미 만료되었거나 다른 상태면 IllegalStateException 이다.
	 * 승인 시작 때 제한 시각을 늘려 두므로 정상 흐름에서는 일어나지 않고, 일어나면 토스에서는 결제가 됐는데 예매는 어긋난 것이라
	 * 호출한 쪽의 트랜잭션을 되돌려 결제를 승인 중(PROCESSING)으로 남기고 스케줄러가 확정하게 한다.
	 */
	@Transactional
	public Booking markPaid(Long bookingId, LocalDateTime now) {
		if (bookingRepository.markPaid(bookingId, now) != 1) {
			throw new IllegalStateException("결제 대기 예매가 아니어서 결제 완료로 바꾸지 못했다. 예매 번호: " + bookingId);
		}
		return bookingRepository.findById(bookingId).orElseThrow();
	}

	/** 결제 준비에서 확정한 수령 방법과 배송 정보를 예매에 넣는다. 읽어 온 예매가 같은 트랜잭션에 있어서 커밋 때 저장된다. */
	@Transactional
	public void applyReceiveInfo(Booking booking, ReceiveMethod receiveType, String recipientName,
		String recipientPhone, String recipientAddress) {
		booking.changeReceiveInfo(receiveType, recipientName, recipientPhone, recipientAddress);
	}

	/** 예매의 좌석 수. 결제창에 보여 줄 주문명에 쓴다. */
	@Transactional(readOnly = true)
	public long countSeats(Long bookingId) {
		return bookingSeatRepository.countByBookingId(bookingId);
	}

	/**
	 * 결제 제한 시각이 지난 결제 대기 예매를 최대 batchSize 건 만료(EXPIRED) 처리하고 좌석을 비활성(active=false)으로 바꾼다.
	 * 호출한 쪽의 트랜잭션에 참여한다. 예매 행은 이 트랜잭션이 끝날 때까지 잠기고, 다른 서버는 잠긴 행을 건너뛴다.
	 * 좌석 도메인의 상태(HELD)는 이 서비스가 바꾸지 않고, 되돌릴 판매 좌석 번호만 돌려줘서 파사드가 같은 트랜잭션에서 되돌린다.
	 *
	 * @return 이번에 만료 처리한 예매들의 판매 좌석 번호. 처리할 예매가 없으면 빈 목록이다
	 */
	@Transactional
	public List<Long> expireOverdue(LocalDateTime now, int batchSize) {
		List<Long> bookingIds = bookingRepository.lockOverdueIds(now, batchSize);
		if (bookingIds.isEmpty()) {
			return List.of();
		}
		bookingRepository.markExpired(bookingIds, now);
		List<Long> scheduleSeatIds = bookingSeatRepository.findActiveScheduleSeatIds(bookingIds);
		bookingSeatRepository.deactivateByBookingIds(bookingIds);
		return scheduleSeatIds;
	}

	/**
	 * 결제 대기(PENDING) 예매와 예매 좌석을 만든다. 호출한 쪽의 트랜잭션에 참여한다.
	 * 좌석 점유와 같은 트랜잭션에서 불러야 "점유는 됐는데 예매는 없는" 좌석이 생기지 않는다.
	 * 예매번호가 겹치거나 같은 좌석이 이미 활성 예매에 있으면 DB 의 UNIQUE 인덱스가 막는다.
	 */
	@Transactional
	public BookingResponse.Created create(BookingCreateCommand command) {
		int ticketAmount = command.seats().stream().mapToInt(BookingCreateCommand.SeatLine::price).sum();
		int bookingFeeAmount = command.bookingFee() * command.seats().size();

		Booking booking = bookingRepository.save(Booking.builder()
			.bookingNo(BookingNumbers.next(command.today()))
			.userId(command.userId())
			.scheduleId(command.scheduleId())
			.performanceId(command.performanceId())
			.status(BookingStatus.PENDING)
			.receiveType(command.receiveMethod())
			.ordererName(command.ordererName())
			.ordererPhone(command.ordererPhone())
			.ordererEmail(command.ordererEmail())
			.ticketAmount(ticketAmount)
			.bookingFeeAmount(bookingFeeAmount)
			.totalAmount(ticketAmount + bookingFeeAmount)
			.cancelPolicySnapshot(objectMapper.writeValueAsString(command.cancelRules()))
			.expiresAt(command.expiresAt())
			.build());

		List<BookingSeat> seats = bookingSeatRepository.saveAll(command.seats().stream()
			.map(line -> BookingSeat.builder()
				.bookingId(booking.getId())
				.scheduleSeatId(line.scheduleSeatId())
				.zoneName(line.zoneName())
				.rowLabel(line.rowLabel())
				.seatNumber(line.seatNumber())
				.gradeName(line.gradeName())
				.price(line.price())
				.bookingFee(command.bookingFee())
				.active(true)
				.build())
			.toList());
		return BookingResponse.Created.from(booking, seats);
	}
}
