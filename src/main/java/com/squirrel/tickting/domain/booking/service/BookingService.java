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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class BookingService {

	/** 좌석을 차지한 예매 상태. 전체 취소(CANCELED)와 만료(EXPIRED)는 좌석을 돌려줬으므로 뺀다. */
	private static final List<BookingStatus> OCCUPYING_STATUSES =
		List.of(BookingStatus.PENDING, BookingStatus.PAID, BookingStatus.PARTIAL_CANCELED);
	/** 돈을 낸 좌석이 남아 있는 예매 상태. */
	private static final List<BookingStatus> PAID_STATUSES =
		List.of(BookingStatus.PAID, BookingStatus.PARTIAL_CANCELED);

	private final BookingRepository bookingRepository;
	private final BookingSeatRepository bookingSeatRepository;
	private final ObjectMapper objectMapper;
	private final CancelPolicyCalculator cancelPolicyCalculator;

	/**
	 * 한 사람이 한 공연에서 이미 가진 활성 좌석 수. 결제 대기 좌석도 포함한다.
	 * 분산락(사용자·공연) 안에서 불러야 동시에 들어온 두 요청이 같은 값을 보고 함께 통과하지 않는다.
	 */
	@Transactional(readOnly = true)
	public long countActiveSeats(Long userId, Long performanceId) {
		return bookingSeatRepository.countActiveByUserAndPerformance(userId, performanceId);
	}

	/**
	 * 이 예매의 예매자가 그 공연의 1인 최대 매수를 결제한 좌석만으로 채웠는가. 결제 뒤 대기열 자리를 반납할지 정하는 데 쓴다.
	 * 결제 대기 좌석은 세지 않는다. 만료되면 다시 살 수 있어서, 그때까지 자리를 남겨 둬야 줄을 다시 서지 않는다.
	 */
	@Transactional(readOnly = true)
	public boolean hasPaidUpToLimit(Long bookingId) {
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		long paid = bookingSeatRepository.countActiveByUserAndPerformanceAndStatusIn(booking.getUserId(),
			booking.getPerformanceId(), PAID_STATUSES);
		return paid >= booking.getPerformance().getMaxPerPerson();
	}

	/**
	 * 그 공연에 좌석을 차지한 예매(결제 대기·결제 완료·일부 취소)가 있는가. 전체 취소·만료된 예매는 좌석을 차지하지 않아 세지 않는다.
	 * 판매자가 1인 최대 매수를 줄일 수 있는지 판단하는 데 쓴다. 1인 매수 계산(취소·만료 제외)과 같은 기준이다.
	 * 결제 제한 시각이 지났어도 스케줄러가 만료 처리하기 전의 결제 대기 예매는 센다. (줄이지 못하는 쪽이 안전하다)
	 */
	@Transactional(readOnly = true)
	public boolean existsOccupying(Long performanceId) {
		return bookingRepository.existsByPerformanceIdAndStatusIn(performanceId, OCCUPYING_STATUSES);
	}

	/**
	 * 그 회차에 만료(EXPIRED)가 아닌 예매가 있는가. 판매자가 회차 일시를 바꿀 수 있는지 판단하는 데 쓴다.
	 * 좌석을 돌려준 전체 취소도 세는 점이 existsOccupying 과 다르다. 취소한 예매의 내역에도 지금 일시가 보이기 때문이다.
	 * 결제 제한 시각이 지났어도 만료 처리 전의 결제 대기 예매는 센다.
	 */
	@Transactional(readOnly = true)
	public boolean existsNotExpired(Long scheduleId) {
		return bookingRepository.existsByScheduleIdAndStatusNot(scheduleId, BookingStatus.EXPIRED);
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

	/** 예매를 번호로 읽는다. 사용자와 상관없이 처리하는 작업(결제 보정)이 예매의 현재 상태를 보는 데 쓴다. 없으면 IllegalStateException 이다. */
	@Transactional(readOnly = true)
	public Booking getById(Long bookingId) {
		return bookingRepository.findById(bookingId)
			.orElseThrow(() -> new IllegalStateException("예매가 없다. 예매 번호: " + bookingId));
	}

	/**
	 * 결제 대기 예매 하나를 제한 시각을 기다리지 않고 바로 만료(EXPIRED) 처리하고 좌석을 비활성(active=false)으로 바꾼다.
	 * 결제가 되지 않았다고 토스에서 확인된 예매를 닫을 때 쓴다. 결제 대기가 아니면(이미 만료·결제·취소) 아무것도 하지 않는다.
	 * 호출한 쪽의 트랜잭션에 참여하고, 좌석 도메인의 상태는 바꾸지 않고 되돌릴 판매 좌석 번호만 돌려준다.
	 *
	 * @return 되돌릴 판매 좌석 번호. 만료 처리하지 않았으면 빈 목록이다
	 */
	@Transactional
	public List<Long> expire(Long bookingId, LocalDateTime now) {
		if (bookingRepository.markExpired(List.of(bookingId), now) == 0) {
			return List.of();
		}
		List<Long> scheduleSeatIds = bookingSeatRepository.findActiveScheduleSeatIds(List.of(bookingId));
		bookingSeatRepository.deactivateByBookingIds(List.of(bookingId));
		return scheduleSeatIds;
	}

	/**
	 * 만료된 예매를 취소로 바꾼다. 만료된 뒤에 토스에서 결제된 것이 확인돼 환불할 때 쓴다. 만료(EXPIRED) 예매만 바꾸고 아니면 아무것도 하지 않는다.
	 * 좌석은 만료될 때 이미 풀려서 여기서 다시 건드리지 않는다.
	 */
	@Transactional
	public void cancelExpired(Long bookingId, LocalDateTime now) {
		bookingRepository.markExpiredAsCanceled(bookingId, now);
	}

	/**
	 * 예매 취소에서 취소할 좌석과 금액을 계산한다. 아무것도 바꾸지 않는다. 호출한 쪽의 트랜잭션과 예매 단위 락 안에서 불러야
	 * 계산한 뒤 반영하기 전에 다른 취소가 끼어들지 않는다.
	 *
	 * 판정 순서: 없거나 남의 예매 404 → 결제 완료·일부 취소가 아님 409 → 취소 불가 기간 400 → 취소할 수 없는 좌석 400
	 *
	 * @param bookingSeatIds 취소할 예매 좌석. 비어 있으면(null 포함) 남은 좌석 전부다. 같은 번호가 여러 번 와도 한 번으로 본다
	 */
	@Transactional
	public BookingCancelPlan planCancel(Long userId, Long bookingId, List<Long> bookingSeatIds, LocalDateTime now) {
		Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
			.orElseThrow(() -> new BusinessException(BookingExceptionCode.BOOKING_NOT_FOUND));
		if (booking.getStatus() != BookingStatus.PAID && booking.getStatus() != BookingStatus.PARTIAL_CANCELED) {
			throw new BusinessException(BookingExceptionCode.BOOKING_NOT_CANCELABLE);
		}
		CancelPolicyCalculator.Quote quote = cancelPolicyCalculator.quote(booking, now);
		if (!quote.cancelable()) {
			throw new BusinessException(BookingExceptionCode.CANCEL_PERIOD_OVER);
		}

		List<BookingSeat> activeSeats = booking.getBookingSeats().stream().filter(BookingSeat::isActive).toList();
		List<BookingSeat> targets = selectTargets(activeSeats, bookingSeatIds);

		int feeRate = quote.feeRate();
		List<BookingCancelPlan.Line> lines = targets.stream()
			.map(seat -> new BookingCancelPlan.Line(seat.getId(), seat.getScheduleSeatId(),
				CancelPolicyCalculator.seatFee(seat.getPrice(), feeRate)))
			.toList();
		int cancelFeeTotal = lines.stream().mapToInt(BookingCancelPlan.Line::cancelFee).sum();
		// 예매 수수료는 취소한 좌석 몫만 돌려준다. (좌석 1장당 붙는 수수료라서. 임시 정책)
		int refundAmount = targets.stream().mapToInt(seat -> seat.getPrice() + seat.getBookingFee()).sum()
			- cancelFeeTotal;
		return new BookingCancelPlan(bookingId, feeRate, lines, cancelFeeTotal, refundAmount,
			targets.size() == activeSeats.size());
	}

	/**
	 * 계산한 취소를 반영한다. 좌석마다 취소 수수료와 환불 번호를 적고 예매 상태를 일부 취소·전체 취소로 바꾼다.
	 * 상태 조건이 있는 UPDATE 라서 계산한 뒤 다른 요청이 먼저 취소했으면 바뀐 행 수가 모자라고, 예외로 트랜잭션 전체(환불 행 포함)를 되돌린다.
	 * 좌석 도메인의 상태(SOLD)는 바꾸지 않고, 되돌릴 판매 좌석 번호만 돌려줘서 파사드가 같은 트랜잭션에서 되돌린다.
	 *
	 * @return 다시 팔 수 있게 되돌릴 판매 좌석 번호. 끝난 회차의 좌석을 지워 번호가 없는 좌석은 빠진다
	 */
	@Transactional
	public List<Long> applyCancel(BookingCancelPlan plan, Long refundId, LocalDateTime now) {
		for (BookingCancelPlan.Line line : plan.lines()) {
			if (bookingSeatRepository.cancel(line.bookingSeatId(), plan.bookingId(), line.cancelFee(), refundId, now) != 1) {
				throw new BusinessException(BookingExceptionCode.SEAT_NOT_CANCELABLE);
			}
		}
		int changed = plan.fullyCanceled()
			? bookingRepository.markCanceled(plan.bookingId(), now)
			: bookingRepository.markPartiallyCanceled(plan.bookingId(), now);
		if (changed != 1) {
			throw new BusinessException(BookingExceptionCode.BOOKING_NOT_CANCELABLE);
		}
		return plan.lines().stream()
			.map(BookingCancelPlan.Line::scheduleSeatId)
			.filter(Objects::nonNull)
			.toList();
	}

	/** 요청한 좌석이 모두 이 예매의 활성 좌석이어야 한다. 하나라도 아니면(이미 취소됨, 다른 예매의 좌석) 전체를 거절한다. */
	private static List<BookingSeat> selectTargets(List<BookingSeat> activeSeats, List<Long> bookingSeatIds) {
		if (bookingSeatIds == null || bookingSeatIds.isEmpty()) {
			return activeSeats;
		}
		Set<Long> requested = new LinkedHashSet<>(bookingSeatIds);
		List<BookingSeat> targets = activeSeats.stream().filter(seat -> requested.contains(seat.getId())).toList();
		if (targets.size() != requested.size()) {
			throw new BusinessException(BookingExceptionCode.SEAT_NOT_CANCELABLE);
		}
		return targets;
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
			.businessId(command.businessId())
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
