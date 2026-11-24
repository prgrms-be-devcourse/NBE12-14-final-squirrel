package com.squirrel.tickting.domain.admin.type;

/** 관리자 상태. 가입이 없고 시드로만 만들어서 삭제 대신 사용 중지로 막는다. */
public enum AdminStatus {
	/** 사용 중 */
	ACTIVE,
	/** 사용 중지. 로그인과 토큰 재발급이 막힌다. */
	INACTIVE
}
