package com.squirrel.tickting.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 리프레시 토큰 쿠키 설정 (app.cookie.*).
 * secure 는 운영이 안전한 쪽(true)이 기본이고, 로컬 프로필에서만 false 로 낮춘다.
 */
@ConfigurationProperties(prefix = "app.cookie")
public record AuthCookieProperties(
	boolean secure,
	String sameSite,
	String path
) {
}
