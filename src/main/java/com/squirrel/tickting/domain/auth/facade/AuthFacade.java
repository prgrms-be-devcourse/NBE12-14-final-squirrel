package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.admin.service.AdminMemberService;
import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.auth.service.EmailVerificationCodeService;
import com.squirrel.tickting.domain.auth.service.TokenService;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.seller.service.SellerSignupCommand;
import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.service.TermsService;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
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
 * 가입·로그인·재발급처럼 여러 도메인(약관, 예매자, 판매자, 토큰)이 얽히는 흐름을 이어 주는 파사드.
 *
 * 각 서비스는 자기 도메인만 다루고 서로 호출하지 않는다. 여러 서비스를 엮는 일은 여기서만 한다.
 * 그래서 "어느 트랜잭션에 무엇이 묶이는지"가 이 클래스 한 곳에서 보인다.
 */
// v1(JWT) 로그인 흐름이라 TokenService(@Deprecated)를 쓰는 것을 허용한다.
@SuppressWarnings("removal")
@Component
@RequiredArgsConstructor
public class AuthFacade {

	private final UserMemberService userMemberService;
	private final SellerMemberService sellerMemberService;
	private final AdminMemberService adminMemberService;
	private final TermsService termsService;
	private final TokenService tokenService;
	private final TransactionTemplate transactionTemplate;
	private final LoginFailureLimiter loginFailureLimiter;
	private final EmailVerificationCodeService emailVerificationCodeService;
	private final Clock clock;

	/**
	 * 예매자 가입. 약관 확인, 회원 저장, 약관 동의 기록이 한 트랜잭션이다.
	 *
	 * 토큰 발급은 트랜잭션 밖에 둔다. DB 연결을 오래 잡지 않으려고
	 * 메서드 전체에 @Transactional 을 걸지 않고 저장 부분만 TransactionTemplate 으로 묶는다.
	 * 동시 요청의 중복(DB UNIQUE 인덱스 위반)은 GlobalExceptionHandler 가 409 로 바꾼다.
	 */
	public AuthResponse.Token userSignup(AuthRequest.UserSignup request, HttpServletResponse response) {
		// Redis 만 보는 검사라 DB 를 쓰는 약관·중복 확인보다 먼저 한다
		String email = EmailUtil.normalize(request.email());
		emailVerificationCodeService.requireVerified(email);
		LocalDateTime verifiedAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

		CustomUserDetails account = transactionTemplate.execute(status -> {
			List<Terms> agreedTerms = termsService.validateAgreement(request.agreedTermIds());
			UserMember user = userMemberService.create(request.email(), request.password(), request.passwordConfirm(),
				request.nickname(), request.name(), request.phone(), verifiedAt);
			termsService.recordAgreements(user.getId(), agreedTerms);
			return userMemberService.toAccount(user);
		});
		// 가입에 성공한 인증은 다시 쓰지 못하게 지운다. 실패한 가입은 지우지 않아 인증을 다시 하지 않고 고쳐 낼 수 있다
		emailVerificationCodeService.clearVerified(email);
		return tokenService.issue(account, response);
	}

	public AuthResponse.Token userLogin(AuthRequest.Login request, HttpServletRequest httpRequest, HttpServletResponse response) {
		CustomUserDetails account = loginFailureLimiter.login(AccountType.USER, request.email(), httpRequest.getRemoteAddr(),
			() -> userMemberService.login(request.email(), request.password()));
		return tokenService.issue(account, response);
	}

	/** 판매자는 관리자 승인 전에는 로그인할 수 없으므로 토큰을 주지 않고 신청 결과만 돌려준다. */
	public AuthResponse.SellerSignup sellerSignup(AuthRequest.SellerSignup request) {
		SellerBusiness business = sellerMemberService.signup(new SellerSignupCommand(
			request.email(), request.password(), request.passwordConfirm(), request.name(), request.phone(),
			request.companyName(),
			request.representativeName(), request.businessNumber(), request.contactPhone(),
			request.contactEmail()));
		return new AuthResponse.SellerSignup(business.getId(), business.getStatus().name());
	}

	public AuthResponse.Token sellerLogin(AuthRequest.Login request, HttpServletRequest httpRequest, HttpServletResponse response) {
		CustomUserDetails account = loginFailureLimiter.login(AccountType.SELLER, request.email(), httpRequest.getRemoteAddr(),
			() -> sellerMemberService.login(request.email(), request.password()));
		return tokenService.issue(account, response);
	}

	/** 관리자는 가입이 없다. 시드로 만든 계정으로 로그인한다. */
	public AuthResponse.Token adminLogin(AuthRequest.Login request, HttpServletRequest httpRequest, HttpServletResponse response) {
		CustomUserDetails account = loginFailureLimiter.login(AccountType.ADMIN, request.email(), httpRequest.getRemoteAddr(),
			() -> adminMemberService.login(request.email(), request.password()));
		return tokenService.issue(account, response);
	}

	/** 쿠키의 리프레시 토큰으로 재발급한다. 권한(역할, 사업체)은 토큰이 아니라 DB 값으로 다시 만든다. */
	public AuthResponse.Token refresh(String refreshToken, HttpServletResponse response) {
		CustomUserDetails identity = tokenService.readRefreshToken(refreshToken);

		CustomUserDetails account = switch (identity.getAccountType()) {
			case USER -> userMemberService.getLoginAccount(identity.getId());
			case SELLER -> sellerMemberService.getLoginAccount(identity.getId());
			case ADMIN -> adminMemberService.getLoginAccount(identity.getId());
		};
		return tokenService.issue(account, response);
	}
}
