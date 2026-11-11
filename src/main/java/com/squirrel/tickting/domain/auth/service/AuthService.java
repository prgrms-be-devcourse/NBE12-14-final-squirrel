package com.squirrel.tickting.domain.auth.service;

import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.auth.identity.IdentityVerifier;
import com.squirrel.tickting.domain.auth.identity.VerifiedIdentity;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.seller.service.SellerSignupCommand;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 가입·로그인 흐름을 이어 준다. 본인인증 확인 → 계정 서비스 → 토큰 발급 순서다.
 * 이 클래스에는 트랜잭션을 걸지 않는다. (저장은 각 계정 서비스가 자기 트랜잭션으로 한다)
 */
@Service
@RequiredArgsConstructor
public class AuthService {

	private final IdentityVerifier identityVerifier;
	private final UserMemberService userMemberService;
	private final SellerMemberService sellerMemberService;
	private final TokenService tokenService;

	public AuthResponse.Token userSignup(AuthRequest.UserSignup request, HttpServletResponse response) {
		VerifiedIdentity identity = identityVerifier.verify(request.identityToken());
		CustomUserDetails account = userMemberService.signup(request.email(), request.password(),
			request.nickname(), identity, request.agreedTermIds());
		return tokenService.issue(account, response);
	}

	public AuthResponse.Token userLogin(AuthRequest.Login request, HttpServletResponse response) {
		CustomUserDetails account = userMemberService.login(request.email(), request.password());
		return tokenService.issue(account, response);
	}

	/** 판매자는 관리자 승인 전에는 로그인할 수 없으므로 토큰을 주지 않고 신청 결과만 돌려준다. */
	public AuthResponse.SellerSignup sellerSignup(AuthRequest.SellerSignup request) {
		VerifiedIdentity identity = identityVerifier.verify(request.identityToken());
		SellerBusiness business = sellerMemberService.signup(new SellerSignupCommand(
			request.email(), request.password(), identity, request.companyName(),
			request.representativeName(), request.businessNumber(), request.contactPhone(),
			request.contactEmail()));
		return new AuthResponse.SellerSignup(business.getId(), business.getStatus().name());
	}

	public AuthResponse.Token sellerLogin(AuthRequest.Login request, HttpServletResponse response) {
		CustomUserDetails account = sellerMemberService.login(request.email(), request.password());
		return tokenService.issue(account, response);
	}

	public AuthResponse.Token refresh(String refreshToken, HttpServletResponse response) {
		return tokenService.refresh(refreshToken, response);
	}
}
