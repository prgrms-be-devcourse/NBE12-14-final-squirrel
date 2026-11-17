package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.service.CancelPolicySaveCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 판매자의 취소 규정 요청. 생성(SLR-21)과 수정(SLR-22)이 같은 본문을 쓴다. */
public class SellerCancelPolicyRequest {

	private SellerCancelPolicyRequest() {
	}

	/**
	 * 취소 규정 생성·수정 본문. 구간 하나씩의 범위는 여기서 보고, 구간끼리의 관계(빈 날짜·겹침 등)는 서비스가 본다.
	 * 이름의 공백 제거는 검증보다 먼저 일어나야 해서 생성자에서 한다. (공백만 있는 이름이 @NotBlank 에 걸리게)
	 */
	@Schema(name = "CancelPolicySaveRequest", description = "취소 규정 생성·수정 요청")
	public record Save(

		// 50자는 화면에 무리 없이 보이는 길이로 작게 시작한 값이다. (DB 컬럼은 100자라 나중에 늘릴 수 있다)
		@NotBlank(message = "규정 이름을 입력해 주세요.")
		@Size(max = 50, message = "규정 이름은 50자 이하로 입력해 주세요.")
		@Schema(description = "규정 이름. 같은 사업체 안에서 겹칠 수 없다(대소문자 무시)", example = "공연장 자체 규정")
		String name,

		@NotNull(message = "규정 구간을 입력해 주세요.")
		@Size(min = 1, max = 10, message = "규정 구간은 1개 이상 10개 이하로 입력해 주세요.")
		@Schema(description = "구간. 순서는 자유이고, 당일(0일 전)부터 제한 없음까지 빈 날짜·겹치는 날짜 없이 이어져야 한다")
		List<@NotNull(message = "규정 구간을 입력해 주세요.") @Valid Rule> rules
	) {

		public Save {
			name = name == null ? null : name.trim();
		}

		public CancelPolicySaveCommand toCommand() {
			List<CancelPolicySaveCommand.Rule> ruleCommands = rules.stream()
				.map(rule -> new CancelPolicySaveCommand.Rule(
					rule.daysFrom(), rule.daysTo(), rule.feeRate(), rule.cancelable()))
				.toList();
			return new CancelPolicySaveCommand(name, ruleCommands);
		}
	}

	/** 구간 하나. 일수 상한 90은 늘리기는 쉽고 줄이기는 기존 규정이 걸려서 작게 시작한 값이다. */
	@Schema(name = "CancelPolicyRuleRequest", description = "취소 규정 구간")
	public record Rule(

		@NotNull(message = "구간 시작 일수를 입력해 주세요.")
		@Min(value = 0, message = "구간 일수는 0 이상이어야 합니다.")
		@Max(value = 90, message = "구간 일수는 90 이하여야 합니다.")
		@Schema(description = "공연 며칠 전부터(이 날 포함)", example = "3")
		Integer daysFrom,

		@Min(value = 0, message = "구간 일수는 0 이상이어야 합니다.")
		@Max(value = 90, message = "구간 일수는 90 이하여야 합니다.")
		@Schema(description = "공연 며칠 전까지(이 날 포함). null 이면 제한 없음", example = "6")
		Integer daysTo,

		@NotNull(message = "수수료율을 입력해 주세요.")
		@Min(value = 0, message = "수수료율은 0 이상이어야 합니다.")
		@Max(value = 100, message = "수수료율은 100 이하여야 합니다.")
		@Schema(description = "취소 수수료율(%). 취소 불가 구간이면 계산에 쓰지 않는다", example = "10")
		Integer feeRate,

		@NotNull(message = "취소 가능 여부를 입력해 주세요.")
		@Schema(description = "false 면 이 구간에는 취소할 수 없다", example = "true")
		Boolean cancelable
	) {
	}
}
