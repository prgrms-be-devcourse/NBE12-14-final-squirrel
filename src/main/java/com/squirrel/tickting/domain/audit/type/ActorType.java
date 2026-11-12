package com.squirrel.tickting.domain.audit.type;

/** 감사 로그의 행위자 종류. 계정 테이블이 서로 달라서 종류 + 번호로 가리킨다. */
public enum ActorType {

	SELLER,
	ADMIN,
	/** 시스템(스케줄러). actor_id 는 비운다. */
	SYSTEM
}
