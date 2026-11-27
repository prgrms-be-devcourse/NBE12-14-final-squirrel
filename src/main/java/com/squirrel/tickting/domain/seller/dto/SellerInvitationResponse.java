package com.squirrel.tickting.domain.seller.dto;

import com.squirrel.tickting.domain.seller.infra.entity.SellerInvitation;
import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
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

	@Schema(name = "SellerInvitationValidityResponse", description = "초대 링크 확인 결과")
	public record Validity(

		@Schema(description = "링크가 유효한지. 만료·사용됨·다시 초대해서 무효·없는 토큰은 모두 false", example = "true")
		boolean valid,

		@Schema(description = "가입할 이메일. valid 가 false 면 null", example = "staff@company.com")
		String email,

		@Schema(description = "초대한 회사 이름. valid 가 false 면 null", example = "(주)가상기획")
		String companyName
	) {

		public static Validity from(SellerInvitation invitation) {
			return new Validity(true, invitation.getEmail(), invitation.getBusiness().getCompanyName());
		}

		public static Validity invalid() {
			return new Validity(false, null, null);
		}
	}

	@Schema(name = "SellerInvitationAcceptedResponse", description = "담당자 가입 결과. 자동 로그인은 하지 않는다")
	public record Accepted(

		@Schema(description = "만든 담당자 계정 번호", example = "12")
		Long memberId,

		@Schema(description = "로그인 이메일", example = "staff@company.com")
		String email,

		@Schema(description = "소속 회사 이름", example = "(주)가상기획")
		String companyName
	) {

		public static Accepted from(SellerMember member) {
			return new Accepted(member.getId(), member.getEmail(), member.getBusiness().getCompanyName());
		}
	}
}
