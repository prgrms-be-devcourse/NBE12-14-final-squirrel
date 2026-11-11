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

	/** JWT 서명 키로 쓸 수 있는 길이(48바이트)의 무작위 문자열 */
	public static String randomJwtSecret() {
		byte[] bytes = new byte[48];
		RANDOM.nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}
}
