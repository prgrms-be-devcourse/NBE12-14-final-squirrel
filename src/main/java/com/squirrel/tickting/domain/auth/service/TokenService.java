package com.squirrel.tickting.domain.auth.service;

import com.squirrel.tickting.domain.auth.dto.AuthResponse;
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
 * 토큰 발급과 검증만 한다. (계정 서비스를 호출하지 않는다)
 * 액세스 토큰은 응답 본문, 리프레시 토큰은 HttpOnly 쿠키로 내려준다.
 *
 * 아직 리프레시 토큰을 서버에 저장하지 않아서 로그아웃으로 무효화할 수 없다. (Redis 작업 때 추가)
 * 대신 AuthFacade 가 재발급할 때마다 DB 에서 계정 상태를 다시 읽어, 정지·탈퇴된 계정은 재발급을 막는다.
 */
@Service
@RequiredArgsConstructor
public class TokenService {

	private final JwtProvider jwtProvider;
	private final JwtProperties jwtProperties;
	private final CookieUtil cookieUtil;

	public AuthResponse.Token issue(CustomUserDetails account, HttpServletResponse response) {
		String accessToken = jwtProvider.createAccessToken(account);
		String refreshToken = jwtProvider.createRefreshToken(account.getId(), account.getAccountType());
		cookieUtil.addRefreshCookie(response, refreshToken);
		return AuthResponse.Token.of(accessToken, jwtProperties.accessTokenValiditySeconds(), account);
	}

	/** 쿠키의 리프레시 토큰을 검증하고 누구의 토큰인지(번호, 계정 종류)만 돌려준다. */
	public CustomUserDetails readRefreshToken(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new BusinessException(AuthExceptionCode.TOKEN_MISSING);
		}
		return jwtProvider.parseRefreshToken(refreshToken);
	}
}
