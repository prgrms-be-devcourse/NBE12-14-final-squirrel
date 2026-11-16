package com.squirrel.tickting.global.jwt;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 jwt.* 설정.
 * secret 은 HS 서명용이라 32바이트 이상이어야 한다. (짧으면 앱이 뜨지 않는다)
 *
 * JWT 방식(v1). 새 코드는 Redis 세션 방식(v2)을 쓴다. 이 클래스를 새로 사용하면 빌드가 실패한다.
 * 기존 v1 코드에서 쓰는 곳에는 @SuppressWarnings("removal") 로 허용한 표시가 있다.
 *
 * @deprecated v2(Redis 세션) 도입으로 쓰지 않는다. v1 을 없애는 날 함께 지운다.
 */
@Deprecated(since = "2026-11-14", forRemoval = true)
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
	String secret,
	String issuer,
	long accessTokenValiditySeconds,
	long refreshTokenValiditySeconds
) {

	private static final int MIN_SECRET_BYTES = 32;

	/**
	 * 서명 키가 없거나 짧으면 일찍, 원인이 보이게 실패시킨다.
	 * ${JWT_SECRET} 처럼 환경 변수를 못 찾으면 스프링이 에러 없이 "${JWT_SECRET}" 글자 그대로를 값으로 넣는데,
	 * 이 경우 jjwt 의 "키가 안전하지 않다"는 영문 메시지만 나와서 원인을 찾기 어렵다.
	 */
	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES
			|| secret.contains("${")) {
			throw new IllegalStateException(
				"jwt.secret 이 설정되지 않았거나 32바이트보다 짧습니다. "
					+ "프로젝트 루트 .env 에 JWT_SECRET=(32자 이상) 을 추가하세요.");
		}
	}
}
