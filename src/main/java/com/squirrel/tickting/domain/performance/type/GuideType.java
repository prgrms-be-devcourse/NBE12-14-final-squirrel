package com.squirrel.tickting.domain.performance.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 모든 공연에 똑같이 보여주는 공통 안내의 종류. */
@Getter
@RequiredArgsConstructor
public enum GuideType {

	/** 예매 안내 */
	BOOKING_GUIDE("예매 안내"),
	/** 환불 안내 */
	REFUND_GUIDE("환불 안내");

	private final String label;
}
