package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.admin.service.AdminMemberService;
import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * v2(Redis 세션) 로그인 흐름. 계정을 확인하는 부분은 v1(AuthFacade)과 같은 서비스를 쓰고,
 * 확인이 끝나면 토큰을 만드는 대신 SessionAuthenticator 로 세션을 만든다.
 *
 * v1(JWT)을 건드리지 않으려고 따로 두었다.
 */
@Component
@RequiredArgsConstructor
public class AuthSessionFacade {

	private final UserMemberService userMemberService;
	private final SellerMemberService sellerMemberService;
	private final AdminMemberService adminMemberService;
	private final SessionAuthenticator sessionAuthenticator;

	public AuthResponse.Session userLogin(AuthRequest.Login request, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		return startSession(userMemberService.login(request.email(), request.password()), httpRequest, httpResponse);
	}

	/** 사업체가 승인된 판매자만 로그인된다. (v1 과 같은 검사) */
	public AuthResponse.Session sellerLogin(AuthRequest.Login request, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		return startSession(sellerMemberService.login(request.email(), request.password()), httpRequest, httpResponse);
	}

	public AuthResponse.Session adminLogin(AuthRequest.Login request, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		return startSession(adminMemberService.login(request.email(), request.password()), httpRequest, httpResponse);
	}

	private AuthResponse.Session startSession(CustomUserDetails account, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		sessionAuthenticator.login(account, httpRequest, httpResponse);
		return AuthResponse.Session.of(account);
	}
}
