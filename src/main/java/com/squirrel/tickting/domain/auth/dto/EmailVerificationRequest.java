package com.squirrel.tickting.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class EmailVerificationRequest {

	private EmailVerificationRequest() {
	}

	/** 인증번호 확인. 형식이 틀리면 Redis 를 보기 전에 400 으로 돌려보낸다. */
	@Schema(name = "EmailVerificationConfirmRequest", description = "이메일 인증번호 확인 요청")
	public record Confirm(

		@NotBlank(message = "인증번호를 입력해 주세요.")
		@Pattern(regexp = "^[0-9]{6}$", message = "인증번호는 숫자 6자리입니다.")
		@Schema(description = "메일로 받은 인증번호 (숫자 6자리)", example = "123456")
		String code
	) {
	}
}
