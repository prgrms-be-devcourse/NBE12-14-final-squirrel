package com.squirrel.tickting.domain.payment.dto;

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
}
