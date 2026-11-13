package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.admin.service.AdminMemberService;
import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.auth.identity.IdentityVerifier;
import com.squirrel.tickting.domain.auth.identity.VerifiedIdentity;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.service.TermsService;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * v2(Redis 세션) 가입·로그인 흐름. 계정을 확인하는 부분은 v1(AuthFacade)과 같은 서비스를 쓰고,
 * 확인이 끝나면 토큰을 만드는 대신 SessionAuthenticator 로 세션을 만든다.
 *
 * v1(JWT)을 건드리지 않으려고 따로 두었다. 가입처럼 겹치는 흐름은 v1 을 없애는 날 한 곳으로 합친다.
 */
@Component
@RequiredArgsConstructor
public class AuthSessionFacade {

	private final IdentityVerifier identityVerifier;
	private final UserMemberService userMemberService;
	private final SellerMemberService sellerMemberService;
	private final AdminMemberService adminMemberService;
	private final TermsService termsService;
	private final SessionAuthenticator sessionAuthenticator;
	private final TransactionTemplate transactionTemplate;

	/** 예매자 가입. 약관 확인, 회원 저장, 약관 동의 기록이 한 트랜잭션이고, 가입하면 바로 로그인(세션 생성)된다. */
	public AuthResponse.Session userSignup(AuthRequest.UserSignup request, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		VerifiedIdentity identity = identityVerifier.verify(request.identityToken());

		CustomUserDetails account = transactionTemplate.execute(status -> {
			List<Terms> agreedTerms = termsService.validateAgreement(request.agreedTermIds());
			UserMember user = userMemberService.create(request.email(), request.password(),
				request.nickname(), identity);
			termsService.recordAgreements(user.getId(), agreedTerms);
			return userMemberService.toAccount(user);
		});
		return startSession(account, httpRequest, httpResponse);
	}

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
