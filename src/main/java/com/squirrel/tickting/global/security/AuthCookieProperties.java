package com.squirrel.tickting.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 쿠키 설정 (app.cookie.*). v1 의 리프레시 토큰 쿠키와 v2 의 SESSION 쿠키가 secure, sameSite 를 함께 쓴다.
 * path 는 리프레시 토큰 쿠키만 쓴다. (SESSION 쿠키는 모든 경로에 보낸다)
 * secure 는 운영이 안전한 쪽(true)이 기본이고, 로컬 프로필에서만 false 로 낮춘다.
 */
@ConfigurationProperties(prefix = "app.cookie")
public record AuthCookieProperties(
	boolean secure,
	String sameSite,
	String path
) {
}
