package com.squirrel.tickting.domain.auth.service;

import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.jwt.JwtProvider;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.util.CookieUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 토큰 발급과 재발급.
 * 액세스 토큰은 응답 본문, 리프레시 토큰은 HttpOnly 쿠키로 내려준다.
 *
 * 아직 리프레시 토큰을 서버에 저장하지 않아서 로그아웃으로 무효화할 수 없다. (Redis 작업 때 추가)
 * 대신 재발급할 때마다 DB 에서 계정 상태를 다시 읽어, 정지·탈퇴·승인 취소된 계정은 재발급을 막는다.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

	private final JwtProvider jwtProvider;
	private final JwtProperties jwtProperties;
	private final CookieUtil cookieUtil;
	private final UserMemberService userMemberService;
	private final SellerMemberService sellerMemberService;

	public AuthResponse.Token issue(CustomUserDetails account, HttpServletResponse response) {
		String accessToken = jwtProvider.createAccessToken(account);
		String refreshToken = jwtProvider.createRefreshToken(account.getId(), account.getAccountType());
		cookieUtil.addRefreshCookie(response, refreshToken);
		return AuthResponse.Token.of(accessToken, jwtProperties.accessTokenValiditySeconds(), account);
	}

	public AuthResponse.Token refresh(String refreshToken, HttpServletResponse response) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new BusinessException(AuthExceptionCode.TOKEN_MISSING);
		}
		CustomUserDetails identity = jwtProvider.parseRefreshToken(refreshToken);

		// 권한(역할, 사업체)은 토큰이 아니라 DB 값으로 다시 만든다.
		CustomUserDetails account = switch (identity.getAccountType()) {
			case USER -> userMemberService.getLoginAccount(identity.getId());
			case SELLER -> sellerMemberService.getLoginAccount(identity.getId());
			// 관리자 로그인은 아직 없다. 이 토큰은 발급될 수 없으므로 위조로 본다.
			case ADMIN -> throw new BusinessException(AuthExceptionCode.TOKEN_INVALID);
		};
		return issue(account, response);
	}
}
