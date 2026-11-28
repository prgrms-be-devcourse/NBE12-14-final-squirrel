package com.squirrel.tickting.domain.admin.dto;

import com.squirrel.tickting.domain.venue.type.VenueDeactivationMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class AdminVenueRequest {

	private AdminVenueRequest() {
	}

	/**
	 * 공연장 사용 중지(ADM-11). 방식에 기본값을 두지 않는다. 기본값이 있으면 실수로 전부 취소하거나 놓친다.
	 * 사유는 전부 취소하면 회차 취소 사유 앞에 "공연장 사용 중지: " 를 붙여 저장해서(컬럼 500자) 400자로 둔다.
	 */
	@Schema(name = "AdminVenueDeactivateRequest", description = "공연장 사용 중지 요청")
	public record Deactivate(

		@NotBlank(message = "중지 사유를 입력해 주세요.")
		@Size(max = 400, message = "중지 사유는 400자 이하로 입력해 주세요.")
		@Schema(description = "중지 사유. 전부 취소하면 판매자와 예매자에게 보인다", example = "리모델링 공사로 대관 중단")
		String reason,

		@NotNull(message = "중지 방식을 선택해 주세요.")
		@Schema(description = "BLOCK_NEW 새 일정만 막기 / CANCEL_ALL 기존 회차도 전부 취소", example = "BLOCK_NEW")
		VenueDeactivationMode mode
	) {

		public Deactivate {
			reason = reason == null ? null : reason.trim();
		}
	}
}
