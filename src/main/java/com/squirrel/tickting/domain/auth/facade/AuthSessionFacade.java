package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.admin.service.AdminMemberService;
import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.auth.service.EmailVerificationCodeService;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.service.TermsService;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.security.SessionAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.squirrel.tickting.global.util.EmailUtil;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

	private final UserMemberService userMemberService;
	private final SellerMemberService sellerMemberService;
	private final AdminMemberService adminMemberService;
	private final TermsService termsService;
	private final SessionAuthenticator sessionAuthenticator;
	private final TransactionTemplate transactionTemplate;
	private final LoginFailureLimiter loginFailureLimiter;
	private final EmailVerificationCodeService emailVerificationCodeService;
	private final Clock clock;

	/** 예매자 가입. 약관 확인, 회원 저장, 약관 동의 기록이 한 트랜잭션이고, 가입하면 바로 로그인(세션 생성)된다. */
	public AuthResponse.Session userSignup(AuthRequest.UserSignup request, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		// Redis 만 보는 검사라 DB 를 쓰는 약관·중복 확인보다 먼저 한다
		String email = EmailUtil.normalize(request.email());
		emailVerificationCodeService.requireVerified(AccountType.USER, email);
		LocalDateTime verifiedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

		CustomUserDetails account = transactionTemplate.execute(status -> {
			List<Terms> agreedTerms = termsService.validateAgreement(request.agreedTermIds());
			UserMember user = userMemberService.create(request.email(), request.password(), request.passwordConfirm(),
				request.nickname(), request.name(), request.phone(), verifiedAt);
			termsService.recordAgreements(user.getId(), agreedTerms);
			return userMemberService.toAccount(user);
		});
		// 가입에 성공한 인증은 다시 쓰지 못하게 지운다. 실패한 가입은 지우지 않아 인증을 다시 하지 않고 고쳐 낼 수 있다
		emailVerificationCodeService.clearVerified(AccountType.USER, email);
		return startSession(account, httpRequest, httpResponse);
	}

	public AuthResponse.Session userLogin(AuthRequest.Login request, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		CustomUserDetails account = loginFailureLimiter.login(AccountType.USER, request.email(), httpRequest.getRemoteAddr(),
			() -> userMemberService.login(request.email(), request.password()));
		return startSession(account, httpRequest, httpResponse);
	}

	/** 사업체가 승인된 판매자만 로그인된다. (v1 과 같은 검사) */
	public AuthResponse.Session sellerLogin(AuthRequest.Login request, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		CustomUserDetails account = loginFailureLimiter.login(AccountType.SELLER, request.email(), httpRequest.getRemoteAddr(),
			() -> sellerMemberService.login(request.email(), request.password()));
		return startSession(account, httpRequest, httpResponse);
	}

	public AuthResponse.Session adminLogin(AuthRequest.Login request, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		CustomUserDetails account = loginFailureLimiter.login(AccountType.ADMIN, request.email(), httpRequest.getRemoteAddr(),
			() -> adminMemberService.login(request.email(), request.password()));
		return startSession(account, httpRequest, httpResponse);
	}

	private AuthResponse.Session startSession(CustomUserDetails account, HttpServletRequest httpRequest,
		HttpServletResponse httpResponse) {
		sessionAuthenticator.login(account, httpRequest, httpResponse);
		return AuthResponse.Session.of(account);
	}
}
