package com.squirrel.tickting.domain.payment.type;

/** 환불 사유 종류. */
public enum RefundReasonType {

	/** 본인 취소 */
	USER_CANCEL,
	/** 공연·회차 취소 */
	PERFORMANCE_CANCEL,
	/** 연기로 인한 취소 */
	POSTPONE_CANCEL,
	/** 시스템 자동 처리 (결제 불일치 보정 등) */
	SYSTEM,
	/** 관리자 처리 */
	ADMIN
}
