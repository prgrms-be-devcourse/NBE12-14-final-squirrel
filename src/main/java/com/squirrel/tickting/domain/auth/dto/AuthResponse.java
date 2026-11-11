package com.squirrel.tickting.domain.auth.dto;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.media.Schema;

public class AuthResponse {

	private AuthResponse() {
	}

	/** 액세스 토큰은 응답 본문으로, 리프레시 토큰은 HttpOnly 쿠키로 내려간다. */
	@Schema(name = "AuthToken", description = "로그인·가입·재발급 결과")
	public record Token(
		@Schema(description = "액세스 토큰. 요청 헤더에 Authorization: Bearer {accessToken} 으로 보낸다")
		String accessToken,
		@Schema(example = "Bearer")
		String tokenType,
		@Schema(description = "액세스 토큰 유효 시간(초)", example = "900")
		long expiresIn,
		@Schema(description = "계정 종류", example = "USER")
		AccountType accountType,
		@Schema(description = "판매자 역할(OWNER, STAFF). 판매자가 아니면 null")
		String role
	) {

		public static Token of(String accessToken, long expiresIn, CustomUserDetails account) {
			return new Token(accessToken, "Bearer", expiresIn, account.getAccountType(), account.getRole());
		}
	}

	@Schema(name = "SellerSignupResult", description = "판매자 가입 신청 결과")
	public record SellerSignup(
		@Schema(description = "사업체 번호")
		Long businessId,
		@Schema(description = "사업체 상태. 승인 전에는 PENDING 이라 로그인할 수 없다", example = "PENDING")
		String status
	) {
	}

	@Schema(name = "Me", description = "로그인한 계정 정보 (토큰에서 꺼낸 값)")
	public record Me(
		Long id,
		AccountType accountType,
		@Schema(description = "판매자 역할. 판매자가 아니면 null")
		String role,
		@Schema(description = "판매자 소속 사업체 번호. 판매자가 아니면 null")
		Long businessId
	) {

		public static Me from(CustomUserDetails account) {
			return new Me(account.getId(), account.getAccountType(), account.getRole(), account.getBusinessId());
		}
	}
}
