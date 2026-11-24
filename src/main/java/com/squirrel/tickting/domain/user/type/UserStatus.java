package com.squirrel.tickting.domain.user.type;

/** 예매자 상태. 탈퇴는 상태가 아니라 deleted_at 으로 구분한다. */
public enum UserStatus {
	/** 사용 중 */
	ACTIVE,
	/** 이용 제한 (의심 계정 등) */
	SUSPENDED
}
