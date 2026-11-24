package com.squirrel.tickting.domain.performance.type;

/** 공연 구역의 등급 설정 상태. */
public enum ZoneAssignmentStatus {
	/** 아직 판매자가 설정하지 않음. */
	UNSET,
	/** 판매할 등급을 지정함. */
	ASSIGNED,
	/** 의도적으로 판매하지 않음. */
	INTENTIONALLY_UNSOLD
}
