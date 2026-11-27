package com.squirrel.tickting.domain.venue.type;

/** 공연장 사용 중지(ADM-11) 방식. */
public enum VenueDeactivationMode {

	/** 새 일정만 막는다. 이미 만든 회차는 그대로 판다 */
	BLOCK_NEW,
	/** 이 공연장 공개 공연의 시작하지 않은(일정 미정 포함) 회차를 모두 취소한다. 되돌릴 수 없다 */
	CANCEL_ALL
}
