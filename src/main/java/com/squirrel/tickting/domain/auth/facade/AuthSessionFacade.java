package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.admin.service.AdminMemberService;
import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.auth.service.EmailVerificationCodeService;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.seller.service.SellerSignupCommand;
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
 * 가입·로그인처럼 여러 도메인(약관, 예매자, 판매자, 관리자)이 얽히는 흐름을 이어 주는 파사드.
 * 계정을 확인한 뒤 SessionAuthenticator 로 Redis 세션을 만든다.
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

	/**
	 * 판매자 가입 신청. 로그인 이메일의 인증을 마친 뒤에만 받는다. (예매자 가입과 같은 규칙이고, 인증 상태는 판매자용이 따로 있다)
	 * 판매자는 관리자 승인 전에는 로그인할 수 없으므로 세션을 만들지 않고 신청 결과만 돌려준다.
	 */
	public AuthResponse.SellerSignup sellerSignup(AuthRequest.SellerSignup request) {
		// Redis 만 보는 검사라 DB 를 쓰는 중복 확인보다 먼저 한다
		String email = EmailUtil.normalize(request.email());
		emailVerificationCodeService.requireVerified(AccountType.SELLER, email);

		SellerBusiness business = sellerMemberService.signup(new SellerSignupCommand(
			request.email(), request.password(), request.passwordConfirm(), request.name(), request.phone(),
			request.companyName(),
			request.representativeName(), request.businessNumber(), request.contactPhone(),
			request.contactEmail()));
		// 가입에 성공한 인증은 다시 쓰지 못하게 지운다. 실패한 가입은 지우지 않아 인증을 다시 하지 않고 고쳐 낼 수 있다
		emailVerificationCodeService.clearVerified(AccountType.SELLER, email);
		return new AuthResponse.SellerSignup(business.getId(), business.getStatus().name());
	}

	/** 사업체가 승인된 판매자만 로그인된다. */
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
