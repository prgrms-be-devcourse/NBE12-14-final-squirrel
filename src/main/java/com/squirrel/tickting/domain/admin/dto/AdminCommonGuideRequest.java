package com.squirrel.tickting.domain.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminCommonGuideRequest {

	private AdminCommonGuideRequest() {
	}

	/** 공통 안내 수정(ADM-18). 길이는 임시 정책이다. (컬럼은 text 라 제한이 없다) 공연 상세에 그대로 보여서 일반 텍스트만 받는다. */
	@Schema(name = "AdminCommonGuideUpdateRequest", description = "공통 안내 수정 요청")
	public record Update(

		@NotBlank(message = "안내 내용을 입력해 주세요.")
		@Size(max = 2000, message = "안내 내용은 2000자 이하로 입력해 주세요.")
		@Schema(description = "안내 내용 (일반 텍스트, 줄바꿈 가능, 최대 2000자). 앞뒤 공백은 잘라서 저장한다",
			example = "좌석을 선택한 뒤 7분 안에 결제해야 합니다. 시간이 지나면 선택한 좌석이 풀립니다.")
		String content
	) {
	}
}
