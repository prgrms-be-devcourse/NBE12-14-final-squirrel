package com.squirrel.tickting.domain.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 담당자 초대 요청 (AUTH-23~25) */
public final class SellerInvitationRequest {

	private SellerInvitationRequest() {
	}

	@Schema(name = "SellerInvitationInviteRequest", description = "담당자 초대 요청 (대표)")
	public record Invite(

		@NotBlank @Email @Size(max = 255)
		@Schema(description = "초대할 담당자 이메일. 가입하면 이 이메일이 로그인 이메일이 된다", example = "staff@company.com")
		String email
	) {

		public Invite {
			email = trim(email);
		}
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}
}
