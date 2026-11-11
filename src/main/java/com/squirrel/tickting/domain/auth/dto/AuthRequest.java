package com.squirrel.tickting.domain.auth.dto;

import com.squirrel.tickting.global.validation.ValidPassword;
import com.squirrel.tickting.global.validation.ValidPhone;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public class AuthRequest {

	private AuthRequest() {
	}

	@Schema(name = "UserSignupRequest", description = "예매자 회원가입 요청")
	public record UserSignup(

		@NotBlank @Email @Size(max = 255)
		@Schema(description = "로그인 이메일 (대소문자 구분 없이 같은 이메일로 본다)", example = "user@example.com")
		String email,

		@NotBlank @ValidPassword
		@Schema(description = "비밀번호 (8~25자, 영문·숫자·특수문자 포함)", example = "password1!")
		String password,

		@NotBlank @Size(min = 2, max = 20, message = "닉네임은 2글자 이상 20글자 이하로 입력해 주세요.")
		@Schema(description = "화면에 보이는 닉네임", example = "홍길동")
		String nickname,

		@NotBlank
		@Schema(description = "본인인증 토큰. 본인인증 Mock 형식: mock|이름|CI|휴대폰|생년월일(yyyyMMdd)",
			example = "mock|홍길동|ci-hong-001|01012345678|19900101")
		String identityToken,

		@NotNull
		@Schema(description = "동의한 약관 번호 목록. 최신 필수 약관을 모두 포함해야 한다", example = "[1, 2]")
		List<Long> agreedTermIds
	) {

		public UserSignup {
			email = trim(email);
		}
	}

	@Schema(name = "SellerSignupRequest", description = "판매자(대표자) 가입 신청 요청")
	public record SellerSignup(

		@NotBlank @Email @Size(max = 255)
		@Schema(description = "대표자 로그인 이메일", example = "owner@company.com")
		String email,

		@NotBlank @ValidPassword
		@Schema(description = "비밀번호 (8~25자, 영문·숫자·특수문자 포함)", example = "password1!")
		String password,

		@NotBlank
		@Schema(description = "대표자 본인인증 토큰. 본인인증 Mock 형식: mock|이름|CI|휴대폰|생년월일(yyyyMMdd)",
			example = "mock|김대표|ci-owner-001|01099998888|19800101")
		String identityToken,

		@NotBlank @Size(max = 100)
		@Schema(description = "사업체명(기획사 이름)", example = "(주)가상기획")
		String companyName,

		@NotBlank @Size(max = 50)
		@Schema(description = "대표자명. 본인인증으로 확인된 이름과 같아야 한다", example = "김대표")
		String representativeName,

		@NotBlank @Pattern(regexp = "^\\d{10}$", message = "사업자등록번호는 하이픈 없이 숫자 10자리로 입력해 주세요.")
		@Schema(description = "사업자등록번호 (숫자 10자리)", example = "1234567890")
		String businessNumber,

		@NotBlank @ValidPhone
		@Schema(description = "담당 연락처", example = "01012345678")
		String contactPhone,

		@NotBlank @Email @Size(max = 255)
		@Schema(description = "담당 연락 이메일", example = "contact@company.com")
		String contactEmail,

		@AssertTrue(message = "약관에 동의해야 가입 신청을 할 수 있습니다.")
		@Schema(description = "판매자 약관 동의 여부", example = "true")
		boolean termsAgreed
	) {

		public SellerSignup {
			email = trim(email);
			contactEmail = trim(contactEmail);
		}
	}

	@Schema(name = "LoginRequest", description = "이메일 로그인 요청")
	public record Login(

		@NotBlank @Email
		@Schema(description = "이메일", example = "user@example.com")
		String email,

		@NotBlank
		@Schema(description = "비밀번호", example = "password1!")
		String password
	) {

		public Login {
			email = trim(email);
		}
	}

	/** 붙여 넣다가 생긴 앞뒤 공백 때문에 이메일 형식 검증에서 떨어지지 않도록 검증 전에 잘라 낸다. */
	private static String trim(String value) {
		return value == null ? null : value.trim();
	}
}
