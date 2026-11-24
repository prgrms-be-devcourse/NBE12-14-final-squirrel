package com.squirrel.tickting.domain.auth.dto;

import com.squirrel.tickting.global.validation.ValidPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PasswordResetRequest {

	private PasswordResetRequest() {
	}

	/** 재설정 메일 요청. 계정 종류는 경로(users, sellers)로 정한다. */
	@Schema(name = "PasswordResetMailRequest", description = "비밀번호 재설정 메일 요청")
	public record Mail(

		@NotBlank @Email @Size(max = 255)
		@Schema(description = "가입한 이메일 (대소문자 구분 없이 같은 이메일로 본다)", example = "user@example.com")
		String email
	) {
	}

	/** 링크 확인. 토큰이 주소·서버 로그에 남지 않도록 본문으로 받는다. */
	@Schema(name = "PasswordResetValidateRequest", description = "재설정 링크 확인 요청")
	public record Validate(

		@NotBlank @Size(max = 200)
		@Schema(description = "메일 링크의 토큰")
		String token
	) {
	}

	@Schema(name = "PasswordResetConfirmRequest", description = "비밀번호 재설정 요청")
	public record Confirm(

		@NotBlank @Size(max = 200)
		@Schema(description = "메일 링크의 토큰")
		String token,

		@NotBlank @ValidPassword
		@Schema(description = "새 비밀번호 (8~25자, 영문·숫자·특수문자 포함)", example = "newPassw0rd!")
		String password,

		@NotBlank
		@Schema(description = "새 비밀번호 확인. 비밀번호와 같아야 한다 (다르면 400 AUTH005)", example = "newPassw0rd!")
		String passwordConfirm
	) {
	}
}
