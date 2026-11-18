package com.squirrel.tickting.support;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 테스트에서 쓰는 비밀 값은 코드에 적지 않고 실행할 때마다 새로 만든다.
 * (저장소에 키처럼 보이는 문자열이 남지 않고, .env 에도 의존하지 않는다)
 */
public final class TestSecrets {

	private static final SecureRandom RANDOM = new SecureRandom();

	private TestSecrets() {
	}

	/** 토스 시크릿 키처럼 생긴 무작위 문자열. 실제 키가 아니라서 가짜 토스 서버를 상대로만 쓴다. */
	public static String randomTossSecretKey() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return "test_sk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** AWS 액세스 키 ID 처럼 생긴 무작위 문자열. 실제 키가 아니라서 서명이 만들어지는 모양만 확인할 때 쓴다. */
	public static String randomAwsAccessKeyId() {
		return "AKIA" + randomUpperAlphanumeric(16);
	}

	/** AWS 시크릿 키처럼 생긴 무작위 문자열 (40자) */
	public static String randomAwsSecretKey() {
		byte[] bytes = new byte[30];
		RANDOM.nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}

	private static String randomUpperAlphanumeric(int length) {
		String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < length; i++) {
			builder.append(chars.charAt(RANDOM.nextInt(chars.length())));
		}
		return builder.toString();
	}

	/** JWT 서명 키로 쓸 수 있는 길이(48바이트)의 무작위 문자열 */
	public static String randomJwtSecret() {
		byte[] bytes = new byte[48];
		RANDOM.nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}
}
