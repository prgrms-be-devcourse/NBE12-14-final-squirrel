package com.squirrel.tickting.domain.venue.type;

/** 공연장 상태. 공연이 잡혔을 수 있어서 삭제하지 않고 사용 중지로 바꾼다. */
public enum VenueStatus {

	/** 사용 중 */
	ACTIVE,
	/** 사용 중지. 판매자의 공연장 선택 목록에서 빠진다. */
	INACTIVE
}
