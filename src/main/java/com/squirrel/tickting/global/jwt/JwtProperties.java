package com.squirrel.tickting.global.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 jwt.* 설정.
 * secret 은 HS 서명용이라 32바이트 이상이어야 한다. (짧으면 앱이 뜨지 않는다)
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
	String secret,
	String issuer,
	long accessTokenValiditySeconds,
	long refreshTokenValiditySeconds
) {
}
