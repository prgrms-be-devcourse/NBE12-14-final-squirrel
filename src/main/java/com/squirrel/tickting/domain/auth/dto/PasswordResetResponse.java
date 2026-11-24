package com.squirrel.tickting.domain.auth.dto;

import com.squirrel.tickting.global.security.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

public class PasswordResetResponse {

	private PasswordResetResponse() {
	}

	/** 발송을 접수한 결과. 메일이 도착했다는 뜻이 아니다. 두 시각은 서버가 정해 줘서 화면이 따로 계산하지 않는다. */
	@Builder
	@Schema(name = "PasswordResetSent", description = "재설정 메일 발송 접수 결과")
	public record Sent(
		@Schema(description = "요청한 이메일 그대로. 화면에 \"○○로 보냈습니다\"를 보여 주려는 것")
		String email,
		@Schema(description = "이번 링크의 유효 시각")
		LocalDateTime expiresAt,
		@Schema(description = "다시 요청할 수 있는 시각")
		LocalDateTime resendAvailableAt
	) {
	}

	/** 링크가 유효한지. 만료·사용됨·없는 토큰은 모두 valid=false 이고 계정 종류는 null 이다. 이메일은 담지 않는다. */
	@Builder
	@Schema(name = "PasswordResetValidity", description = "재설정 링크 확인 결과")
	public record Validity(
		@Schema(description = "링크가 유효한지")
		boolean valid,
		@Schema(description = "계정 종류(USER 예매자, SELLER 판매자). 유효하지 않으면 null")
		AccountType accountType
	) {
	}
}
