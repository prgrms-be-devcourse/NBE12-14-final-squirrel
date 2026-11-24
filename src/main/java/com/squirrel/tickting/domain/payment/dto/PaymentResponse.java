package com.squirrel.tickting.domain.payment.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import java.time.LocalDateTime;

public class PaymentResponse {

	private PaymentResponse() {
	}

	/** 결제 준비 결과. 프론트가 토스 결제창을 열 때 쓰는 값이다. */
	public record Prepared(
		Long paymentId,
		String orderId,
		String orderName,
		Integer amount,
		String status,
		LocalDateTime expiresAt
	) {

		public static Prepared from(Payment payment, String orderName, LocalDateTime expiresAt) {
			return new Prepared(payment.getId(), payment.getOrderId(), orderName, payment.getAmount(),
				payment.getStatus().name(), expiresAt);
		}
	}

	/** 결제 승인 결과. 예매가 결제 완료(PAID)가 되었음을 알린다. */
	public record Confirmed(
		Long bookingId,
		String bookingNo,
		String bookingStatus,
		Long paymentId,
		String paymentStatus,
		String method,
		Integer amount,
		LocalDateTime approvedAt
	) {

		public static Confirmed from(Booking booking, Payment payment) {
			return new Confirmed(booking.getId(), booking.getBookingNo(), booking.getStatus().name(), payment.getId(),
				payment.getStatus().name(), payment.getMethod(), payment.getAmount(), payment.getApprovedAt());
		}
	}
}
