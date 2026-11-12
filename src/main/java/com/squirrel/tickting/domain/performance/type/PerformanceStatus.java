package com.squirrel.tickting.domain.performance.type;

/**
 * 공연 상태. 판매 상태(예정·판매 중·종료·매진)는 저장하지 않고 계산한다.
 * 저장하는 상태가 적을수록 값이 서로 안 맞는 버그가 줄어든다.
 */
public enum PerformanceStatus {

	/** 판매자가 작성 중. 예매자에게 보이지 않는다. */
	DRAFT,
	/** 공개됨 */
	PUBLISHED,
	/** 공연 전체 취소 */
	CANCELED
}
