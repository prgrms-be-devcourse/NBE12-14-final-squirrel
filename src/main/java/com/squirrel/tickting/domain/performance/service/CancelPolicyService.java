package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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

	/**
	 * 쓰는 공연이 이 상태면 규정을 고칠 수 없다. 수정(SLR-22)의 409 와 목록·상세의 editable 이 이 값 하나를 함께 써서
	 * "고칠 수 있다고 떴는데 저장하면 409" 가 생기지 않게 한다.
	 * 취소된 공연도 잠그는 것은 판매자가 그 공연에 적용된 기준을 볼 곳이 공연의 규정뿐이라서다.
	 * 상태만 봐서 작성 중(DRAFT)에 취소된 공연도 잠긴다. 공연에 공개 시각(published_at)이 생기면 그 값으로 바꾼다(DB 관련 TODO).
	 */
	private static final Set<PerformanceStatus> LOCKING_STATUSES =
		EnumSet.of(PerformanceStatus.PUBLISHED, PerformanceStatus.CANCELED);

	private final CancelPolicyRepository cancelPolicyRepository;
	private final CancelPolicyRuleRepository cancelPolicyRuleRepository;
	private final PerformanceRepository performanceRepository;

	/**
	 * 서비스 기본 규정과 내 사업체 규정, 그리고 각각 지금 고칠 수 있는지. 다른 사업체의 규정은 나오지 않는다.
	 * 고칠 수 있는지는 페이지의 규정 번호를 모아 쿼리 한 번으로 본다.
	 */
	@Transactional(readOnly = true)
	public Page<Summary> searchVisible(Long businessId, Pageable pageable) {
		Page<CancelPolicy> page = cancelPolicyRepository.searchVisible(businessId, pageable);
		List<Long> ids = page.getContent().stream().map(CancelPolicy::getId).toList();
		Set<Long> inUse = ids.isEmpty() ? Set.of()
			: Set.copyOf(performanceRepository.findCancelPolicyIdsInUse(ids, LOCKING_STATUSES));
		return page.map(policy -> new Summary(policy, isEditable(policy, inUse.contains(policy.getId()))));
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

	/** 볼 수 있는 규정 하나와 그 구간, 지금 고칠 수 있는지. 남의 규정·없는 규정은 getVisible 과 같은 404 다. */
	@Transactional(readOnly = true)
	public Detail getVisibleDetail(Long cancelPolicyId, Long businessId) {
		CancelPolicy policy = getVisible(cancelPolicyId, businessId);
		List<CancelPolicyRule> rules = cancelPolicyRuleRepository.findByPolicyIdOrderByDaysFromDesc(policy.getId());
		return new Detail(policy, rules, isEditable(policy, isInUse(policy.getId())));
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
		List<CancelPolicyRule> rules = saveRules(policy, sorted);
		// 방금 만든 규정은 쓰는 공연이 없어서 항상 고칠 수 있다.
		return new Detail(policy, rules, true);
	}

	/**
	 * 내 사업체 규정의 이름과 구간을 전체 교체한다. DB 를 보지 않는 구간 검사를 먼저 하고,
	 * 규정 행을 잠근 뒤에 "쓰는 공연" 과 이름을 확인해서 확인과 교체 사이에 다른 수정이 끼어들지 않게 한다.
	 * 같은 값으로 보내도 그대로 교체하고 200 이다.
	 */
	@Transactional
	public Detail update(Long cancelPolicyId, Long businessId, SaveCommand command) {
		List<SaveCommand.Rule> sorted = sortAndValidate(command.rules());
		CancelPolicy policy = cancelPolicyRepository.findMineForUpdate(cancelPolicyId, businessId)
			.orElseThrow(() -> notMine(cancelPolicyId, businessId));
		if (isInUse(policy.getId())) {
			throw new BusinessException(PerformanceExceptionCode.CANCEL_POLICY_IN_USE);
		}
		if (cancelPolicyRepository.existsVisibleNameExcept(businessId, command.name(), policy.getId())) {
			throw new BusinessException(PerformanceExceptionCode.CANCEL_POLICY_NAME_DUPLICATED);
		}

		policy.rename(command.name());
		cancelPolicyRuleRepository.deleteByPolicyId(policy.getId());
		List<CancelPolicyRule> rules = saveRules(policy, sorted);
		// 잠금 검사를 통과했으므로 고친 직후에도 고칠 수 있다.
		return new Detail(policy, rules, true);
	}

	/**
	 * 정렬한 구간을 저장한다. 생성과 수정이 함께 쓴다.
	 * 응답은 상세(SLR-20)와 같이 공연에서 먼 구간부터라 거꾸로 저장해서 그대로 돌려준다.
	 */
	private List<CancelPolicyRule> saveRules(CancelPolicy policy, List<SaveCommand.Rule> sortedRules) {
		return cancelPolicyRuleRepository.saveAll(sortedRules.reversed().stream()
			.map(rule -> CancelPolicyRule.builder()
				.policy(policy)
				.daysFrom(rule.daysFrom())
				.daysTo(rule.daysTo())
				.feeRate(rule.feeRate())
				.cancelable(rule.cancelable())
				.build())
			.toList());
	}

	/**
	 * 잠금 조회에서 빠진 규정이 기본 규정인지(409), 없거나 남의 것인지(404) 가른다.
	 * 기본 규정이 아니면 볼 수 있는 규정 조회에도 나오지 않으므로 남의 규정은 없는 규정과 같은 404 가 된다.
	 */
	private BusinessException notMine(Long cancelPolicyId, Long businessId) {
		return cancelPolicyRepository.findVisible(cancelPolicyId, businessId).isPresent()
			? new BusinessException(PerformanceExceptionCode.DEFAULT_CANCEL_POLICY_NOT_EDITABLE)
			: new BusinessException(PerformanceExceptionCode.CANCEL_POLICY_NOT_FOUND);
	}

	/** 이 규정을 쓰는 공연 중에 규정을 잠그는 상태(LOCKING_STATUSES)가 있는가 */
	private boolean isInUse(Long cancelPolicyId) {
		return performanceRepository.existsByCancelPolicyAndStatuses(cancelPolicyId, LOCKING_STATUSES);
	}

	/** 기본 규정이 아니고 잠그는 공연이 쓰지 않으면 고칠 수 있다. 목록의 계산이 상세·수정과 같은 기준을 쓰게 한 곳에 둔다. */
	private boolean isEditable(CancelPolicy policy, boolean inUse) {
		return policy.getBusinessId() != null && !inUse;
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
		List<CancelPolicyRule> rules,
		boolean editable
	) {
	}

	/** 취소 규정 목록의 한 줄. 지금 고칠 수 있는지(editable)를 함께 담는다. */
	public record Summary(
		CancelPolicy policy,
		boolean editable
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
