package com.squirrel.tickting.domain.seller.dto;

import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.type.SellerMemberStatus;
import com.squirrel.tickting.domain.seller.type.SellerRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/** 담당자 관리 응답 (AUTH-26~28) */
public final class SellerMemberResponse {

	private SellerMemberResponse() {
	}

	@Schema(name = "SellerMemberItem", description = "내 회사의 계정 한 개")
	public record Member(

		@Schema(description = "계정 번호", example = "12")
		Long memberId,

		@Schema(description = "로그인 이메일", example = "staff@company.com")
		String email,

		@Schema(description = "이름", example = "이담당")
		String name,

		@Schema(description = "휴대폰 번호", example = "01055556666")
		String phone,

		@Schema(description = "역할", example = "STAFF")
		SellerRole role,

		@Schema(description = "상태 (ACTIVE 사용 중, INACTIVE 비활성)", example = "ACTIVE")
		SellerMemberStatus status,

		@Schema(description = "계정을 만든 시각", example = "2026-11-27T14:00:00")
		LocalDateTime createdAt
	) {

		public static Member from(SellerMember member) {
			return new Member(member.getId(), member.getEmail(), member.getName(), member.getPhone(), member.getRole(),
				member.getStatus(), member.getCreatedAt());
		}
	}
}
