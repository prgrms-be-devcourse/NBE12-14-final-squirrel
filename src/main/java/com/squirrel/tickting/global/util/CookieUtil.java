package com.squirrel.tickting.global.util;

import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.security.AuthCookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 HttpOnly 쿠키 생성·삭제.
 * SameSite 를 지정하려고 jakarta Cookie 대신 Spring ResponseCookie 를 쓴다.
 * 액세스 토큰은 쿠키가 아니라 응답 본문으로 내리고, 요청 헤더(Authorization)로 받는다.
 */
@Component
@RequiredArgsConstructor
public class CookieUtil {

	public static final String REFRESH_TOKEN_COOKIE = "refreshToken";

	private final AuthCookieProperties properties;
	private final JwtProperties jwtProperties;

	public void addRefreshCookie(HttpServletResponse response, String token) {
		ResponseCookie cookie = base(token)
			.maxAge(jwtProperties.refreshTokenValiditySeconds())
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	public void clearRefreshCookie(HttpServletResponse response) {
		ResponseCookie cookie = base("").maxAge(0).build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(REFRESH_TOKEN_COOKIE, value)
			.httpOnly(true)
			.secure(properties.secure())
			.sameSite(properties.sameSite())
			.path(properties.path());
	}
}
