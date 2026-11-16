package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.service.CancelPolicyDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
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

	/** 취소 규정 상세. 규정 생성·수정(SLR-21·22)도 이 모양으로 응답한다. */
	@Builder
	@Schema(name = "CancelPolicyDetail", description = "취소 규정 상세")
	public record Detail(
		@Schema(description = "취소 규정 번호")
		Long cancelPolicyId,
		@Schema(description = "규정명", example = "기본 규정")
		String name,
		@Schema(description = "서비스 기본 규정이면 true. 기본 규정은 예시로만 보여 주며 수정할 수 없다")
		boolean isDefault,
		@Schema(description = "구간. 공연에서 먼 구간부터(daysFrom 큰 순)")
		List<Rule> rules
	) {

		public static Detail from(CancelPolicyDetail detail) {
			CancelPolicy policy = detail.policy();
			return Detail.builder()
				.cancelPolicyId(policy.getId())
				.name(policy.getName())
				.isDefault(policy.getBusinessId() == null)
				.rules(detail.rules().stream().map(Rule::from).toList())
				.build();
		}
	}

	@Builder
	@Schema(name = "CancelPolicyRule", description = "취소 규정 구간")
	public record Rule(
		@Schema(description = "공연 며칠 전부터", example = "3")
		Integer daysFrom,
		@Schema(description = "공연 며칠 전까지. null 이면 제한 없음", example = "6")
		Integer daysTo,
		@Schema(description = "취소 수수료율(%)", example = "10")
		Integer feeRate,
		@Schema(description = "false 면 이 구간에는 취소할 수 없다")
		boolean cancelable
	) {

		public static Rule from(CancelPolicyRule rule) {
			return Rule.builder()
				.daysFrom(rule.getDaysFrom())
				.daysTo(rule.getDaysTo())
				.feeRate(rule.getFeeRate())
				.cancelable(rule.isCancelable())
				.build();
		}
	}
}
