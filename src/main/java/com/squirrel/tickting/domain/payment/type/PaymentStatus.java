package com.squirrel.tickting.domain.payment.type;

/** 결제 상태. */
public enum PaymentStatus {

	/** 결제 준비 (토스 승인 전) */
	READY,
	/**
	 * 토스에 승인을 요청하는 중. 요청을 보낸 뒤 서버가 죽거나 응답을 받지 못하면 이 상태로 남고, 스케줄러가 토스에 조회해서 확정한다.
	 * 같은 결제의 승인 요청이 겹치지 않게 막는 표시이기도 하다.
	 */
	PROCESSING,
	/** 승인 완료 */
	DONE,
	/** 실패 */
	FAILED,
	/** 일부 환불 */
	PARTIAL_CANCELED,
	/** 전액 환불 */
	CANCELED
}
