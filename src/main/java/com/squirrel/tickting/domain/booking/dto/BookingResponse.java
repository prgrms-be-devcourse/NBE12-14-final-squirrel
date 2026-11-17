package com.squirrel.tickting.domain.booking.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import java.time.LocalDateTime;
import java.util.List;

public class BookingResponse {

	private BookingResponse() {
	}

	/** 좌석 점유·예매 생성 결과. 좌석 정보는 예매 때 복사해 둔 값이다. */
	public record Created(
		Long bookingId,
		String bookingNo,
		String status,
		LocalDateTime expiresAt,
		List<Seat> seats,
		Integer ticketAmount,
		Integer bookingFeeAmount,
		Integer totalAmount
	) {

		public static Created from(Booking booking, List<BookingSeat> seats) {
			return new Created(
				booking.getId(),
				booking.getBookingNo(),
				booking.getStatus().name(),
				booking.getExpiresAt(),
				seats.stream().map(Seat::from).toList(),
				booking.getTicketAmount(),
				booking.getBookingFeeAmount(),
				booking.getTotalAmount());
		}
	}

	public record Seat(
		Long bookingSeatId,
		String zoneName,
		String rowLabel,
		String seatNumber,
		String gradeName,
		Integer price,
		Integer bookingFee
	) {

		public static Seat from(BookingSeat seat) {
			return new Seat(seat.getId(), seat.getZoneName(), seat.getRowLabel(), seat.getSeatNumber(),
				seat.getGradeName(), seat.getPrice(), seat.getBookingFee());
		}
	}
}
