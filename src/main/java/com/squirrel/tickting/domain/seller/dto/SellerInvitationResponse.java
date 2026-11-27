package com.squirrel.tickting.domain.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 담당자 초대 응답 (AUTH-23~25) */
public final class SellerInvitationResponse {

	private SellerInvitationResponse() {
	}

	@Schema(name = "SellerInvitationInvitedResponse", description = "담당자 초대 결과. 200 은 메일 발송을 접수했다는 뜻이고 도착을 보장하지 않는다")
	public record Invited(

		@Schema(description = "만든 초대 번호", example = "3")
		Long invitationId,

		@Schema(description = "초대한 이메일 (소문자로 맞춘 값)", example = "staff@company.com")
		String email,

		@Schema(description = "링크 유효 시각", example = "2026-11-30T14:00:00")
		LocalDateTime expiresAt
	) {
	}
}
