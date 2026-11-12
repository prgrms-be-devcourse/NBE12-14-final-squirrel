package com.squirrel.tickting.domain.payment.type;

/** 결제 상태. */
public enum PaymentStatus {

	/** 결제 준비 (토스 승인 전) */
	READY,
	/** 승인 완료 */
	DONE,
	/** 실패 */
	FAILED,
	/** 일부 환불 */
	PARTIAL_CANCELED,
	/** 전액 환불 */
	CANCELED
}
