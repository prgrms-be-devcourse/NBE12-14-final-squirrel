package com.squirrel.tickting.domain.seller.dto;

import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.service.SellerBusinessDetail;
import com.squirrel.tickting.domain.seller.type.SellerBusinessStatus;
import com.squirrel.tickting.domain.seller.type.SellerMemberStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

public class SellerBusinessResponse {

	private SellerBusinessResponse() {
	}

	/**
	 * 관리자 사업체 목록의 한 줄. 상세를 눌러서 보는 값(연락처, 반려 사유 등)은 넣지 않고 목록에서 고를 때 필요한 것만 둔다.
	 * 계정 정보와 본인인증 값은 어느 응답에도 넣지 않는다.
	 */
	@Builder
	@Schema(name = "SellerBusinessSummary", description = "판매자 사업체 목록 항목")
	public record Summary(
		@Schema(description = "사업체 번호")
		Long businessId,
		@Schema(description = "상호")
		String companyName,
		@Schema(description = "대표자명")
		String representativeName,
		@Schema(description = "사업자번호 (하이픈 없는 숫자 10자리)")
		String businessNumber,
		@Schema(description = "상태", example = "PENDING")
		SellerBusinessStatus status,
		@Schema(description = "신청 일시")
		LocalDateTime createdAt,
		@Schema(description = "승인·반려 처리 일시. 처리 전에는 null")
		LocalDateTime reviewedAt
	) {

		public static Summary from(SellerBusiness business) {
			return Summary.builder()
				.businessId(business.getId())
				.companyName(business.getCompanyName())
				.representativeName(business.getRepresentativeName())
				.businessNumber(business.getBusinessNumber())
				.status(business.getStatus())
				.createdAt(business.getCreatedAt())
				.reviewedAt(business.getReviewedAt())
				.build();
		}
	}

	/**
	 * 관리자 사업체 상세. 승인·반려를 판단하는 데 필요한 값을 모두 담는다.
	 * 대표 계정(owner)은 신청과 함께 가입한 본인이다. 비밀번호·CI 해시·생년월일 같은 값은 넣지 않는다.
	 * 이름은 본인인증 이름과 대표자명이 같아야 가입되므로 대표자명으로 충분해서 넣지 않는다.
	 */
	@Builder
	@Schema(name = "SellerBusinessDetail", description = "판매자 사업체 상세")
	public record Detail(
		@Schema(description = "사업체 번호")
		Long businessId,
		@Schema(description = "상호")
		String companyName,
		@Schema(description = "대표자명")
		String representativeName,
		@Schema(description = "사업자번호 (하이픈 없는 숫자 10자리)")
		String businessNumber,
		@Schema(description = "업무 연락처 (대표 계정의 휴대폰과 다를 수 있다)")
		String contactPhone,
		@Schema(description = "업무 연락 이메일")
		String contactEmail,
		@Schema(description = "상태", example = "PENDING")
		SellerBusinessStatus status,
		@Schema(description = "반려 사유. 반려된 경우에만 값이 있다")
		String rejectReason,
		@Schema(description = "승인·반려한 관리자 번호. 처리 전에는 null")
		Long reviewedBy,
		@Schema(description = "승인·반려 처리 일시. 처리 전에는 null")
		LocalDateTime reviewedAt,
		@Schema(description = "약관 동의 일시")
		LocalDateTime termsAgreedAt,
		@Schema(description = "신청 일시")
		LocalDateTime createdAt,
		@Schema(description = "대표 계정 (신청과 함께 가입한 사람)")
		Owner owner
	) {

		public static Detail from(SellerBusinessDetail detail) {
			SellerBusiness business = detail.business();
			return Detail.builder()
				.businessId(business.getId())
				.companyName(business.getCompanyName())
				.representativeName(business.getRepresentativeName())
				.businessNumber(business.getBusinessNumber())
				.contactPhone(business.getContactPhone())
				.contactEmail(business.getContactEmail())
				.status(business.getStatus())
				.rejectReason(business.getRejectReason())
				.reviewedBy(business.getReviewedBy())
				.reviewedAt(business.getReviewedAt())
				.termsAgreedAt(business.getTermsAgreedAt())
				.createdAt(business.getCreatedAt())
				.owner(detail.owner() == null ? null : Owner.from(detail.owner()))
				.build();
		}
	}

	@Builder
	@Schema(name = "SellerBusinessOwner", description = "사업체 대표 계정")
	public record Owner(
		@Schema(description = "계정 번호")
		Long memberId,
		@Schema(description = "로그인 이메일")
		String email,
		@Schema(description = "본인인증으로 확인된 휴대폰")
		String phone,
		@Schema(description = "계정 상태", example = "ACTIVE")
		SellerMemberStatus status
	) {

		public static Owner from(SellerMember member) {
			return Owner.builder()
				.memberId(member.getId())
				.email(member.getEmail())
				.phone(member.getPhone())
				.status(member.getStatus())
				.build();
		}
	}
}
