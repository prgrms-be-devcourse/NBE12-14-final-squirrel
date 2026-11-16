package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

public class CancelPolicyResponse {

	private CancelPolicyResponse() {
	}

	/** 취소 규정 목록의 한 줄. 공연 등록 때 고르는 용도라 구간은 넣지 않고, 구간은 상세에서 본다. */
	@Builder
	@Schema(name = "CancelPolicySummary", description = "취소 규정 목록 항목")
	public record Summary(
		@Schema(description = "취소 규정 번호")
		Long cancelPolicyId,
		@Schema(description = "규정명", example = "기본 규정")
		String name,
		@Schema(description = "서비스 기본 규정이면 true. 기본 규정은 예시로만 보여 주며 수정할 수 없다")
		boolean isDefault
	) {

		public static Summary from(CancelPolicy policy) {
			return Summary.builder()
				.cancelPolicyId(policy.getId())
				.name(policy.getName())
				// 사업체 번호가 비어 있는 규정이 서비스 기본 규정이다.
				.isDefault(policy.getBusinessId() == null)
				.build();
		}
	}
}
