package com.squirrel.tickting.global.security;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 로그인하지 않고(토큰 없이) 인증이 필요한 경로를 호출했을 때 401 을 ApiResponse 형식으로 내린다. */
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final HandlerExceptionResolver exceptionResolver;

	public JwtAuthenticationEntryPoint(HandlerExceptionResolver exceptionResolver) {
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
		AuthenticationException authException) {
		exceptionResolver.resolveException(request, response, null,
			new BusinessException(AuthExceptionCode.TOKEN_MISSING));
	}
}
