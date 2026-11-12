package com.squirrel.tickting.domain.payment.type;

/** 환불 처리 상태. 응답이 끊겨도 어디까지 처리됐는지 남도록 요청 직전에 PROCESSING 으로 바꾼다. */
public enum RefundStatus {

	/** 요청됨 */
	REQUESTED,
	/** 토스에 요청 중 */
	PROCESSING,
	/** 완료 */
	DONE,
	/** 실패. 자동으로 3회까지 재시도하고 그래도 실패하면 관리자가 처리한다. */
	FAILED
}
