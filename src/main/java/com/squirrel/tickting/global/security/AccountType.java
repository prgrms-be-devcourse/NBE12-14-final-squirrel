package com.squirrel.tickting.global.security;

/**
 * 계정 종류. 예매자·판매자·관리자는 서로 다른 테이블이라 id 가 겹칠 수 있다.
 * 그래서 토큰과 Redis 키에는 항상 id 와 함께 계정 종류를 붙인다. (예: USER:5)
 */
public enum AccountType {
	USER,
	SELLER,
	ADMIN
}
