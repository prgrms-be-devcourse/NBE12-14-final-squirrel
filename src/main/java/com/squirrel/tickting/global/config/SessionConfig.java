package com.squirrel.tickting.global.config;

import com.squirrel.tickting.global.security.AuthCookieProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * v2(Redis 세션)의 SESSION 쿠키 설정.
 *
 * server.servlet.session.cookie.* 를 yaml 에 적어도 Spring Session 의 쿠키에는 적용되지 않았다.
 * (HttpOnly 와 SameSite 가 빠진 쿠키가 내려갔다) 그래서 직접 지정하고, 값은 리프레시 쿠키와 같은 app.cookie.* 를 쓴다.
 * 로컬(http)에서만 secure 를 끈다.
 */
@Configuration
@RequiredArgsConstructor
public class SessionConfig {

	private final AuthCookieProperties cookieProperties;

	@Bean
	public CookieSerializer cookieSerializer() {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName("SESSION");
		serializer.setCookiePath("/");
		// 스크립트로 세션 번호를 읽지 못하게 한다. (XSS 로 세션을 훔치는 것 방지)
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(cookieProperties.secure());
		// 다른 사이트에서 보내는 쓰기 요청(POST 등)에는 쿠키가 실리지 않게 한다. (CSRF 완화)
		serializer.setSameSite(cookieProperties.sameSite());
		return serializer;
	}
}
