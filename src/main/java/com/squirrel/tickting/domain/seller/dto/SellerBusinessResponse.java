package com.squirrel.tickting.domain.seller.dto;

import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.type.SellerBusinessStatus;
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
}
