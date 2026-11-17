package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.dto.BookingResponse;
import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import com.squirrel.tickting.domain.booking.infra.repository.BookingRepository;
import com.squirrel.tickting.domain.booking.infra.repository.BookingSeatRepository;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import java.util.List;
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
