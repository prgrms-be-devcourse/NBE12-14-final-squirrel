package com.squirrel.tickting.domain.payment.dto;

import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class PaymentRequest {

	private PaymentRequest() {
	}

	/**
	 * 결제 준비. 배송(DELIVERY)이면 받는 사람 정보가 필수인데, 이 조건은 공연이 허용하는 수령 방법과 같이 따져야 해서 파사드가 확인한다.
	 * 현장 수령(ONSITE)이면 받는 사람 정보는 있어도 무시한다.
	 */
	public record Prepare(
		@NotNull(message = "수령 방법을 선택해 주세요.")
		ReceiveMethod receiveType,
		@Size(max = 50, message = "받는 사람 이름은 50자 이하로 입력해 주세요.")
		String recipientName,
		@Size(max = 20, message = "받는 사람 연락처는 20자 이하로 입력해 주세요.")
		String recipientPhone,
		@Size(max = 500, message = "받는 사람 주소는 500자 이하로 입력해 주세요.")
		String recipientAddress
	) {
	}

	/**
	 * 결제 승인. 토스 결제창이 성공하면 successUrl 로 돌아오는 세 값이다.
	 * amount 는 믿지 않는다. 서버가 계산해 저장한 금액과 비교만 하고, 토스에 보내는 금액도 저장한 값이다.
	 */
	public record Confirm(
		@NotBlank(message = "결제 키가 비어 있습니다.")
		@Size(max = 200, message = "결제 키가 너무 깁니다.")
		String paymentKey,
		@NotBlank(message = "주문 ID가 비어 있습니다.")
		@Size(max = 100, message = "주문 ID가 너무 깁니다.")
		String orderId,
		@NotNull(message = "결제 금액이 비어 있습니다.")
		@Positive(message = "결제 금액이 올바르지 않습니다.")
		Integer amount
	) {
	}
}
