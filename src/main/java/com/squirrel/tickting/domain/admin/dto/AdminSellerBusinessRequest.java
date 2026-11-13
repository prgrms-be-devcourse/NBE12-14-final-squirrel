package com.squirrel.tickting.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminSellerBusinessRequest {

	private AdminSellerBusinessRequest() {
	}

	@Schema(name = "SellerBusinessRejectRequest", description = "사업체 반려 요청")
	public record Reject(

		// 500자는 seller_business.reject_reason 컬럼 크기다
		@NotBlank(message = "반려 사유를 입력해 주세요.")
		@Size(max = 500, message = "반려 사유는 500자 이하로 입력해 주세요.")
		@Schema(description = "반려 사유 (최대 500자). 앞뒤 공백은 잘라서 저장한다", example = "사업자등록번호 확인이 필요합니다.")
		String reason
	) {
	}
}
