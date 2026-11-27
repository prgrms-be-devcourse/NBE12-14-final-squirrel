package com.squirrel.tickting.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminInquiryRequest {

	private AdminInquiryRequest() {
	}

	@Schema(name = "AdminInquiryAnswerRequest", description = "문의 답변 요청")
	public record Answer(

		// 2000자는 inquiry.answer 컬럼 크기다
		@NotBlank(message = "답변을 입력해 주세요.")
		@Size(max = 2000, message = "답변은 2000자 이하로 입력해 주세요.")
		@Schema(description = "답변 (최대 2000자). 앞뒤 공백은 잘라서 저장한다", example = "환불은 카드사 사정에 따라 3~5영업일 걸립니다.")
		String answer
	) {
	}
}
