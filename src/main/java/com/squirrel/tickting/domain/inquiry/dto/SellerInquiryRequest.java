package com.squirrel.tickting.domain.inquiry.dto;

import com.squirrel.tickting.domain.inquiry.dto.UserInquiryRequest.Image;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 판매자 문의 요청. 예매자 문의와 달리 예매를 고르지 않는다. 사진은 예매자 문의와 같은 모양(UserInquiryRequest.Image)을 쓴다. */
public class SellerInquiryRequest {

	private SellerInquiryRequest() {
	}

	/** 문의 작성. 길이·사진 수는 예매자 문의와 같은 임시 정책이고 컬럼 크기와 같다. (doc/inquiry-design.md) */
	@Schema(name = "SellerInquiryCreateRequest", description = "판매자 문의 작성 요청")
	public record Create(
		@NotNull(message = "문의 유형을 선택해 주세요.")
		@Schema(description = "문의 유형. 판매자 유형 목록에 있는 값만", example = "SETTLEMENT")
		InquiryCategory category,

		@NotBlank(message = "제목을 입력해 주세요.")
		@Size(max = 100, message = "제목은 100자 이하로 입력해 주세요.")
		@Schema(description = "제목", example = "정산 금액이 예상과 달라요")
		String title,

		@NotBlank(message = "내용을 입력해 주세요.")
		@Size(max = 2000, message = "내용은 2000자 이하로 입력해 주세요.")
		@Schema(description = "내용", example = "11월 공연 정산 금액이 판매 현황과 다릅니다.")
		String content,

		@Size(max = 3, message = "사진은 3장까지 첨부할 수 있습니다.")
		@Schema(description = "첨부 사진. 비우면 없음. 보낸 순서대로 저장한다")
		List<@Valid @NotNull(message = "사진이 비어 있습니다.") Image> images
	) {
	}
}
