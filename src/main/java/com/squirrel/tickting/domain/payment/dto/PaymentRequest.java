package com.squirrel.tickting.domain.payment.dto;

import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import jakarta.validation.constraints.NotNull;
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
}
