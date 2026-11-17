package com.squirrel.tickting.domain.payment.gateway;

/** 결제 대행사에서 승인됐는지 알 수 없다. 실패로 단정하지 말고 승인 중으로 남겨 두었다가 대행사에 조회해서 확정해야 한다. */
public class GatewayUnavailableException extends RuntimeException {

	public GatewayUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

	public GatewayUnavailableException(String message) {
		super(message);
	}
}
