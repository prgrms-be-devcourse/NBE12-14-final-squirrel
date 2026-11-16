package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CancelPolicyService {

	// 구간 관계 오류는 코드(PERFORMANCE101) 하나에 메시지로 이유를 나눈다.
	static final String RULE_REVERSED = "구간의 끝 일수는 시작 일수보다 작을 수 없습니다.";
	static final String RULE_NOT_FROM_TODAY = "구간은 공연 당일(0일 전)부터 시작해야 합니다.";
	static final String RULE_UNLIMITED_NOT_LAST = "제한 없음 구간은 하나만, 가장 먼 구간에 두어야 합니다.";
	static final String RULE_GAP_OR_OVERLAP = "구간 사이에 빈 날짜나 겹치는 날짜가 있습니다.";

	private final CancelPolicyRepository cancelPolicyRepository;
	private final CancelPolicyRuleRepository cancelPolicyRuleRepository;

	/** 서비스 기본 규정과 내 사업체 규정. 다른 사업체의 규정은 나오지 않는다. */
	@Transactional(readOnly = true)
	public Page<CancelPolicy> searchVisible(Long businessId, Pageable pageable) {
		return cancelPolicyRepository.searchVisible(businessId, pageable);
	}

	/**
	 * 서비스 기본 규정이나 내 사업체 규정 하나. 공연 등록처럼 요청으로 받은 규정 번호를 확인할 때 쓴다.
	 * 남의 규정은 없는 규정과 같은 404로 답한다. (번호가 존재하는지 알려주지 않으려고)
	 */
	@Transactional(readOnly = true)
	public CancelPolicy getVisible(Long cancelPolicyId, Long businessId) {
		return cancelPolicyRepository.findVisible(cancelPolicyId, businessId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.CANCEL_POLICY_NOT_FOUND));
	}

	/** 볼 수 있는 규정 하나와 그 구간들. 남의 규정·없는 규정은 getVisible 과 같은 404 다. */
	@Transactional(readOnly = true)
	public Detail getVisibleDetail(Long cancelPolicyId, Long businessId) {
		CancelPolicy policy = getVisible(cancelPolicyId, businessId);
		List<CancelPolicyRule> rules = cancelPolicyRuleRepository.findByPolicyIdOrderByDaysFromDesc(policy.getId());
		return new Detail(policy, rules);
	}

	/**
	 * 내 사업체 규정을 만든다. DB 를 보지 않는 구간 검사를 먼저 하고 이름 중복을 확인한다.
	 * 같은 이름의 동시 생성은 이 확인을 함께 통과할 수 있다. 마지막 방어선인 UNIQUE (business_id, lower(name)) 는
	 * 아직 없어서 DB 관련 TODO 로 남겨 두었다.
	 */
	@Transactional
	public Detail create(Long businessId, SaveCommand command) {
		List<SaveCommand.Rule> sorted = sortAndValidate(command.rules());
		if (cancelPolicyRepository.existsVisibleName(businessId, command.name())) {
			throw new BusinessException(PerformanceExceptionCode.CANCEL_POLICY_NAME_DUPLICATED);
		}

		CancelPolicy policy = cancelPolicyRepository.save(CancelPolicy.builder()
			.businessId(businessId)
			.name(command.name())
			.build());
		// 응답은 상세(SLR-20)와 같이 공연에서 먼 구간부터라 거꾸로 저장해서 그대로 돌려준다.
		List<CancelPolicyRule> rules = cancelPolicyRuleRepository.saveAll(sorted.reversed().stream()
			.map(rule -> CancelPolicyRule.builder()
				.policy(policy)
				.daysFrom(rule.daysFrom())
				.daysTo(rule.daysTo())
				.feeRate(rule.feeRate())
				.cancelable(rule.cancelable())
				.build())
			.toList());
		return new Detail(policy, rules);
	}

	/**
	 * 구간을 공연에 가까운 순(daysFrom 작은 순)으로 정렬하고, 모든 취소 시점이 정확히 한 구간에 걸리는지 본다.
	 * 구간은 양끝 날짜를 포함하므로 이웃한 구간은 "다음 시작 = 앞 끝 + 1" 이어야 빈 날짜도 겹치는 날짜도 없다.
	 * 입력 순서는 자유라 프론트가 정렬을 빠뜨려도 400 이 나지 않게 서버가 정렬한다.
	 */
	private List<SaveCommand.Rule> sortAndValidate(List<SaveCommand.Rule> rules) {
		List<SaveCommand.Rule> sorted = rules.stream()
			.sorted(Comparator.comparingInt(SaveCommand.Rule::daysFrom))
			.toList();

		for (SaveCommand.Rule rule : sorted) {
			if (rule.daysTo() != null && rule.daysTo() < rule.daysFrom()) {
				throw rulesInvalid(RULE_REVERSED);
			}
		}
		if (sorted.getFirst().daysFrom() != 0) {
			throw rulesInvalid(RULE_NOT_FROM_TODAY);
		}
		for (int i = 0; i < sorted.size() - 1; i++) {
			Integer daysTo = sorted.get(i).daysTo();
			if (daysTo == null) {
				throw rulesInvalid(RULE_UNLIMITED_NOT_LAST);
			}
			if (sorted.get(i + 1).daysFrom() != daysTo + 1) {
				throw rulesInvalid(RULE_GAP_OR_OVERLAP);
			}
		}
		if (sorted.getLast().daysTo() != null) {
			throw rulesInvalid(RULE_UNLIMITED_NOT_LAST);
		}
		return sorted;
	}

	private BusinessException rulesInvalid(String message) {
		return new BusinessException(PerformanceExceptionCode.CANCEL_POLICY_RULES_INVALID, message);
	}

	/**
	 * 취소 규정 상세 조회 결과. 규정 엔티티에는 구간 목록이 없어서 서비스 트랜잭션 안에서 구간을 함께 읽어 담는다.
	 * 규정 생성·수정(SLR-21·22)도 같은 모양으로 응답하므로 이 결과를 다시 쓴다.
	 */
	public record Detail(
		CancelPolicy policy,
		List<CancelPolicyRule> rules
	) {
	}

	/**
	 * 취소 규정 생성·수정(SLR-21·22)에 넣는 값. 서비스가 요청 DTO(SellerCancelPolicyRequest)를 모르게 하려고 따로 둔다.
	 * 사업체 번호는 요청이 아니라 토큰 값이라 서비스 메서드의 인자로 따로 받는다.
	 */
	public record SaveCommand(
		String name,
		List<Rule> rules
	) {

		public SaveCommand {
			rules = List.copyOf(rules);
		}

		/** 구간 하나. 공연 daysFrom 일 전 이상 ~ daysTo 일 전 이하(양끝 포함), daysTo 가 null 이면 제한 없음 */
		public record Rule(int daysFrom, Integer daysTo, int feeRate, boolean cancelable) {
		}
	}
}
