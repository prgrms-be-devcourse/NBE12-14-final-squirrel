package com.squirrel.tickting.global.security;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 로그인은 했지만 권한이 부족한 경로(예: /api/v1/admin/**)를 호출했을 때 403 을 ApiResponse 형식으로 내린다. */
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

	private final HandlerExceptionResolver exceptionResolver;

	public JwtAccessDeniedHandler(HandlerExceptionResolver exceptionResolver) {
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
		AccessDeniedException accessDeniedException) {
		exceptionResolver.resolveException(request, response, null,
			new BusinessException(AuthExceptionCode.ACCESS_DENIED));
	}
}
