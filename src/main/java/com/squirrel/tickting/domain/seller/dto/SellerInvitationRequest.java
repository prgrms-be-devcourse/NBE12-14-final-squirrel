package com.squirrel.tickting.domain.seller.dto;

import com.squirrel.tickting.global.validation.ValidPassword;
import com.squirrel.tickting.global.validation.ValidPhone;
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

	@Schema(name = "SellerInvitationValidateRequest", description = "초대 링크 확인 요청")
	public record Validate(

		@NotBlank
		@Schema(description = "초대 링크의 # 뒤 token 값")
		String token
	) {
	}

	@Schema(name = "SellerInvitationAcceptRequest", description = "담당자 가입 요청. 이메일은 초대받은 이메일 그대로라 받지 않는다")
	public record Accept(

		@NotBlank
		@Schema(description = "초대 링크의 # 뒤 token 값")
		String token,

		@NotBlank @ValidPassword
		@Schema(description = "비밀번호 (8~25자, 영문·숫자·특수문자 포함)", example = "password1!")
		String password,

		@NotBlank
		@Schema(description = "비밀번호 확인. 비밀번호와 같아야 한다 (서비스에서 확인, 다르면 400 AUTH005)", example = "password1!")
		String passwordConfirm,

		@NotBlank @Size(max = 50)
		@Schema(description = "담당자 본인의 이름", example = "이담당")
		String name,

		@NotBlank @ValidPhone
		@Schema(description = "담당자 휴대폰 번호 (하이픈 없이 숫자만)", example = "01055556666")
		String phone
	) {

		public Accept {
			name = trim(name);
		}
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}
}
