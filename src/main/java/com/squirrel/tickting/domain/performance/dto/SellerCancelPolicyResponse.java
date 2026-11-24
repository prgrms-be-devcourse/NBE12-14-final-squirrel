package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

public class SellerCancelPolicyResponse {

	private SellerCancelPolicyResponse() {
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
		boolean isDefault,
		@Schema(description = "지금 수정(SLR-22)할 수 있으면 true. 기본 규정과 공개·취소된 공연이 쓰는 규정은 false. 조회 시점의 안내라 수정할 때 서버가 다시 확인한다")
		boolean editable
	) {

		/** 고칠 수 있는지는 규정 엔티티에 없고 쓰는 공연을 봐야 알아서 서비스가 계산해 넘긴다. */
		public static Summary from(CancelPolicy policy, boolean editable) {
			return Summary.builder()
				.cancelPolicyId(policy.getId())
				.name(policy.getName())
				// 사업체 번호가 비어 있는 규정이 서비스 기본 규정이다.
				.isDefault(policy.getBusinessId() == null)
				.editable(editable)
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
		@Schema(description = "지금 수정(SLR-22)할 수 있으면 true. 기본 규정과 공개·취소된 공연이 쓰는 규정은 false. 조회 시점의 안내라 수정할 때 서버가 다시 확인한다")
		boolean editable,
		@Schema(description = "구간. 공연에서 먼 구간부터(daysFrom 큰 순)")
		List<Rule> rules
	) {

		/** 규정 엔티티에는 구간 목록이 없어서 서비스가 트랜잭션 안에서 구간을 읽어 함께 넘긴다. */
		public static Detail from(CancelPolicy policy, List<CancelPolicyRule> rules, boolean editable) {
			return Detail.builder()
				.cancelPolicyId(policy.getId())
				.name(policy.getName())
				.isDefault(policy.getBusinessId() == null)
				.editable(editable)
				.rules(rules.stream().map(Rule::from).toList())
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
