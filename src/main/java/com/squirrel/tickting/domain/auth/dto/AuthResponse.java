package com.squirrel.tickting.domain.auth.dto;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.media.Schema;

public class AuthResponse {

	private AuthResponse() {
	}

	/** 로그인·가입 결과. 토큰은 없고 SESSION 쿠키로 로그인 상태가 유지된다. */
	@Schema(name = "AuthSession", description = "v2 로그인·가입 결과. 세션 번호는 HttpOnly SESSION 쿠키로 내려간다")
	public record Session(
		@Schema(description = "계정 종류", example = "USER")
		AccountType accountType,
		@Schema(description = "판매자 역할(OWNER, STAFF). 판매자가 아니면 null")
		String role
	) {

		public static Session of(CustomUserDetails account) {
			return new Session(account.getAccountType(), account.getRole());
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
}
