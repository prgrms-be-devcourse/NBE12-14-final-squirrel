package com.squirrel.tickting.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

public class EmailVerificationResponse {

	private EmailVerificationResponse() {
	}

	/** 발송을 접수한 결과. 메일이 도착했다는 뜻이 아니다. 두 시각은 서버가 정해 줘서 화면이 따로 계산하지 않는다. */
	@Builder
	@Schema(name = "EmailVerificationSent", description = "인증번호 발송 접수 결과")
	public record Sent(
		@Schema(description = "이번 인증번호의 유효 시각")
		LocalDateTime expiresAt,
		@Schema(description = "다시 보낼 수 있는 시각")
		LocalDateTime resendAvailableAt
	) {
	}

	@Builder
	@Schema(name = "EmailVerificationConfirmed", description = "이메일 인증 결과")
	public record Confirmed(
		@Schema(description = "이 인증이 유효한 시각. 이때까지 가입을 마쳐야 하고, 넘기면 다시 인증해야 한다")
		LocalDateTime verifiedUntil
	) {
	}
}
