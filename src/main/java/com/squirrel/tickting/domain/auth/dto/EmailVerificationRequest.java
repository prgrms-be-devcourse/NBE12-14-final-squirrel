package com.squirrel.tickting.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class EmailVerificationRequest {

	private EmailVerificationRequest() {
	}

	/** 인증번호 발송. 가입 전이라 로그인이 없어서 인증할 이메일을 요청으로 받는다. */
	@Schema(name = "EmailVerificationSendRequest", description = "가입 전 이메일 인증번호 발송 요청")
	public record Send(

		@NotBlank @Email @Size(max = 255)
		@Schema(description = "가입할 이메일 (대소문자 구분 없이 같은 이메일로 본다)", example = "user@example.com")
		String email
	) {

		/** 가입 요청과 같이 앞뒤 공백을 지운다. (@Email 은 공백이 낀 값을 형식 오류로 본다) */
		public Send {
			email = email == null ? null : email.trim();
		}
	}

	/** 인증번호 확인. 형식이 틀리면 Redis 를 보기 전에 400 으로 돌려보낸다. */
	@Schema(name = "EmailVerificationConfirmRequest", description = "가입 전 이메일 인증번호 확인 요청")
	public record Confirm(

		@NotBlank @Email @Size(max = 255)
		@Schema(description = "인증번호를 받은 이메일", example = "user@example.com")
		String email,

		@NotBlank(message = "인증번호를 입력해 주세요.")
		@Pattern(regexp = "^[0-9]{6}$", message = "인증번호는 숫자 6자리입니다.")
		@Schema(description = "메일로 받은 인증번호 (숫자 6자리)", example = "123456")
		String code
	) {

		public Confirm {
			email = email == null ? null : email.trim();
		}
	}
}
