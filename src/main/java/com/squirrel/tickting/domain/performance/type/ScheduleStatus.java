package com.squirrel.tickting.domain.performance.type;

/** 회차의 판매 상태. 한 회차만 연기하는 경우를 표현하려고 공연이 아니라 회차가 가진다. */
public enum ScheduleStatus {

	/** 판매 중 (오픈 전·종료·매진은 계산해서 표시한다) */
	ON_SALE,
	/** 판매자가 일시 중지 */
	PAUSED,
	/** 연기 */
	POSTPONED,
	/** 이 회차 취소 */
	CANCELED
}
