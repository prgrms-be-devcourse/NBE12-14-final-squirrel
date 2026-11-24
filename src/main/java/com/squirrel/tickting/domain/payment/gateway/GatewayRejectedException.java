package com.squirrel.tickting.domain.payment.gateway;

import lombok.Getter;

/** 결제 대행사가 승인을 거절했다. 결제를 실패로 기록하고 사용자에게 사유를 알려야 한다. */
@Getter
public class GatewayRejectedException extends RuntimeException {

	/** 대행사의 에러 코드 (예: REJECT_CARD_COMPANY) */
	private final String code;

	public GatewayRejectedException(String code, String message) {
		super(message);
		this.code = code;
	}
}
