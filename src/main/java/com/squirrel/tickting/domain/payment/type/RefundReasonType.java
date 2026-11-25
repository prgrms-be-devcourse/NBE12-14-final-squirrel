package com.squirrel.tickting.domain.payment.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 환불 사유 종류.
 * gatewayReason 은 토스에 보내는 취소 사유다. 재시도도 처음과 같은 멱등키로 보내므로 요청 내용이 처음과 같도록 사유를 여기 한곳에 둔다.
 */
@Getter
@RequiredArgsConstructor
public enum RefundReasonType {

	/** 본인 취소 */
	USER_CANCEL("고객 요청 취소"),
	/** 공연·회차 취소 */
	PERFORMANCE_CANCEL("공연 취소"),
	/** 연기로 인한 취소 */
	POSTPONE_CANCEL("공연 연기로 인한 취소"),
	/** 시스템 자동 처리 (결제 불일치 보정 등) */
	SYSTEM("시스템 자동 환불"),
	/** 관리자 처리 */
	ADMIN("관리자 환불");

	private final String gatewayReason;
}
