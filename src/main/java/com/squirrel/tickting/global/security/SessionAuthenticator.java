package com.squirrel.tickting.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * v2 로그인(Redis 세션)에서 세션을 만들고 없애는 일만 한다. 계정 확인(이메일·비밀번호)은 하지 않는다.
 *
 * 세션은 Spring Session 이 Redis 에 저장하고 브라우저에는 SESSION 쿠키만 내려간다.
 * 이후 요청에서는 시큐리티가 쿠키로 세션을 찾아 SecurityContext 를 복원하므로,
 * 컨트롤러에서는 JWT 때와 같이 @AuthenticationPrincipal CustomUserDetails 로 받는다.
 */
@Component
public class SessionAuthenticator {

	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	/** 로그인한 계정을 세션에 저장한다. */
	public void login(CustomUserDetails account, HttpServletRequest request, HttpServletResponse response) {
		// 로그인 전에 만들어진 세션이 있으면 번호를 바꾼다. (미리 알아낸 세션 번호로 로그인 후 세션을 가로채는 공격 방어)
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}

		var authentication = UsernamePasswordAuthenticationToken.authenticated(account, null, account.getAuthorities());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		// 시큐리티는 자동으로 저장하지 않아서 직접 저장해야 Redis 세션에 남는다.
		securityContextRepository.saveContext(context, request, response);
	}

	/** 세션을 지운다. Redis 에서도 사라지므로 같은 쿠키로는 더 이상 로그인되지 않는다. 로그인 상태가 아니어도 오류 없이 지나간다. */
	public void logout(HttpServletRequest request, HttpServletResponse response) {
		logoutHandler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
	}
}
