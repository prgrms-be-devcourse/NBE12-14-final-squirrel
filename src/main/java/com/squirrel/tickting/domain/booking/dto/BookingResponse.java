package com.squirrel.tickting.domain.booking.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
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

	/**
	 * 예매 취소 결과. 좌석 취소는 끝났고, 환불은 status 가 FAILED 면 같은 멱등키로 다시 요청해 마무리한다.
	 * 토스 환불이 실패해도 좌석은 이미 취소되어 있어서, 실패 응답을 주면 사용자가 다시 눌러 "이미 취소된 좌석" 에러를 보게 된다.
	 */
	public record Canceled(
		Long bookingId,
		BookingStatus bookingStatus,
		List<Long> canceledSeatIds,
		Integer cancelFeeTotal,
		Refund refund
	) {
	}

	public record Refund(Long refundId, Integer amount, RefundStatus status) {
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
