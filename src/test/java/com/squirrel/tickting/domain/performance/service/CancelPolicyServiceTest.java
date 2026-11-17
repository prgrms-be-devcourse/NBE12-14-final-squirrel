package com.squirrel.tickting.domain.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.service.CancelPolicySaveCommand.Rule;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * "기본 규정 또는 내 것"이라는 조건과 이름 비교(대소문자 무시)는 쿼리에 있어서 통합 테스트가 확인한다.
 * 여기서는 구간 관계 검사, 생성·수정 흐름(검사 순서, 저장 순서), 수정 가능 여부(editable) 계산을 본다.
 */
@ExtendWith(MockitoExtension.class)
class CancelPolicyServiceTest {

	@Mock
	private CancelPolicyRepository cancelPolicyRepository;
	@Mock
	private CancelPolicyRuleRepository cancelPolicyRuleRepository;
	@Mock
	private PerformanceRepository performanceRepository;

	@InjectMocks
	private CancelPolicyService cancelPolicyService;

	/** 규정을 잠그는 공연 상태. 서비스의 값과 같아야 잠금 검사와 editable 이 같은 기준을 쓴다 */
	private static final Set<PerformanceStatus> LOCKING = EnumSet.of(PerformanceStatus.PUBLISHED, PerformanceStatus.CANCELED);

	@Test
	@DisplayName("볼 수 있는 규정이면 그대로 돌려준다 (내 규정, 기본 규정)")
	void returnsVisiblePolicy() {
		CancelPolicy mine = CancelPolicy.builder().businessId(7L).name("내 규정").build();
		CancelPolicy defaultPolicy = CancelPolicy.builder().name("기본 규정").build();
		when(cancelPolicyRepository.findVisible(10L, 7L)).thenReturn(Optional.of(mine));
		when(cancelPolicyRepository.findVisible(1L, 7L)).thenReturn(Optional.of(defaultPolicy));

		assertThat(cancelPolicyService.getVisible(10L, 7L)).isSameAs(mine);
		assertThat(cancelPolicyService.getVisible(1L, 7L)).isSameAs(defaultPolicy);
	}

	@Test
	@DisplayName("남의 규정이나 없는 규정이라 조회되지 않으면 404 PERFORMANCE100 이다")
	void notVisibleIsNotFound() {
		when(cancelPolicyRepository.findVisible(20L, 7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> cancelPolicyService.getVisible(20L, 7L))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_NOT_FOUND);
	}

	@Test
	@DisplayName("상세는 볼 수 있는 규정과 그 규정의 구간을 함께 돌려주고, 잠그는 공연이 없으면 고칠 수 있다")
	void detailReturnsPolicyWithRules() {
		CancelPolicy mine = CancelPolicy.builder().id(10L).businessId(7L).name("내 규정").build();
		List<CancelPolicyRule> rules = List.of(
			CancelPolicyRule.builder().policy(mine).daysFrom(1).feeRate(0).cancelable(true).build(),
			CancelPolicyRule.builder().policy(mine).daysFrom(0).daysTo(0).feeRate(0).cancelable(false).build());
		when(cancelPolicyRepository.findVisible(10L, 7L)).thenReturn(Optional.of(mine));
		when(cancelPolicyRuleRepository.findByPolicyIdOrderByDaysFromDesc(10L)).thenReturn(rules);
		when(performanceRepository.existsByCancelPolicyAndStatuses(10L, LOCKING)).thenReturn(false);

		CancelPolicyDetail detail = cancelPolicyService.getVisibleDetail(10L, 7L);

		assertThat(detail.policy()).isSameAs(mine);
		assertThat(detail.rules()).isSameAs(rules);
		assertThat(detail.editable()).isTrue();
	}

	@Test
	@DisplayName("상세의 editable 은 공개·취소 공연이 쓰는 내 규정과 기본 규정이면 false 다")
	void detailIsNotEditableWhenInUseOrDefault() {
		CancelPolicy mine = CancelPolicy.builder().id(10L).businessId(7L).name("내 규정").build();
		CancelPolicy defaultPolicy = CancelPolicy.builder().id(1L).name("기본 규정").build();
		when(cancelPolicyRepository.findVisible(10L, 7L)).thenReturn(Optional.of(mine));
		when(cancelPolicyRepository.findVisible(1L, 7L)).thenReturn(Optional.of(defaultPolicy));
		when(performanceRepository.existsByCancelPolicyAndStatuses(10L, LOCKING)).thenReturn(true);

		assertThat(cancelPolicyService.getVisibleDetail(10L, 7L).editable()).isFalse();
		assertThat(cancelPolicyService.getVisibleDetail(1L, 7L).editable()).isFalse();
	}

	@Test
	@DisplayName("목록의 editable 은 페이지의 규정 번호를 모아 쿼리 한 번으로 계산하고, 기본 규정과 쓰이는 규정은 false 다")
	void listComputesEditableWithOneQuery() {
		CancelPolicy defaultPolicy = CancelPolicy.builder().id(1L).name("기본 규정").build();
		CancelPolicy used = CancelPolicy.builder().id(10L).businessId(7L).name("쓰는 규정").build();
		CancelPolicy free = CancelPolicy.builder().id(11L).businessId(7L).name("안 쓰는 규정").build();
		Pageable pageable = PageRequest.of(0, 20);
		when(cancelPolicyRepository.searchVisible(7L, pageable))
			.thenReturn(new PageImpl<>(List.of(defaultPolicy, used, free), pageable, 3));
		when(performanceRepository.findCancelPolicyIdsInUse(List.of(1L, 10L, 11L), LOCKING)).thenReturn(List.of(10L));

		Page<CancelPolicySummary> page = cancelPolicyService.searchVisible(7L, pageable);

		assertThat(page.getContent()).extracting(CancelPolicySummary::editable).containsExactly(false, false, true);
		assertThat(page.getTotalElements()).isEqualTo(3);
	}

	@Test
	@DisplayName("목록이 비어 있으면 쓰는 공연을 조회하지 않는다")
	void emptyListSkipsInUseQuery() {
		Pageable pageable = PageRequest.of(5, 20);
		when(cancelPolicyRepository.searchVisible(7L, pageable)).thenReturn(Page.empty(pageable));

		assertThat(cancelPolicyService.searchVisible(7L, pageable)).isEmpty();
		verifyNoInteractions(performanceRepository);
	}

	@Test
	@DisplayName("상세도 볼 수 없는 규정이면 404 PERFORMANCE100 이고 구간을 읽지 않는다")
	void detailOfNotVisibleIsNotFound() {
		when(cancelPolicyRepository.findVisible(20L, 7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> cancelPolicyService.getVisibleDetail(20L, 7L))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_NOT_FOUND);
		verifyNoInteractions(cancelPolicyRuleRepository);
	}

	// ---- 생성 (SLR-21) ----

	private static Rule rule(int daysFrom, Integer daysTo) {
		return new Rule(daysFrom, daysTo, 0, true);
	}

	private void saveReturnsArgument() {
		when(cancelPolicyRepository.save(any(CancelPolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(cancelPolicyRuleRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
	}

	@Test
	@DisplayName("생성하면 토큰의 사업체 규정으로 저장하고, 구간은 입력 순서와 상관없이 공연에서 먼 구간부터 돌려준다")
	void createSortsRulesFarthestFirst() {
		saveReturnsArgument();
		CancelPolicySaveCommand command = new CancelPolicySaveCommand("내 규정",
			List.of(rule(1, 2), new Rule(7, null, 0, true), new Rule(0, 0, 0, false), new Rule(3, 6, 10, true)));

		CancelPolicyDetail detail = cancelPolicyService.create(7L, command);

		assertThat(detail.editable()).isTrue();
		assertThat(detail.policy().getBusinessId()).isEqualTo(7L);
		assertThat(detail.policy().getName()).isEqualTo("내 규정");
		assertThat(detail.rules()).extracting(CancelPolicyRule::getDaysFrom).containsExactly(7, 3, 1, 0);
		assertThat(detail.rules()).allSatisfy(saved -> assertThat(saved.getPolicy()).isSameAs(detail.policy()));
		CancelPolicyRule sameDay = detail.rules().getLast();
		assertThat(sameDay.getDaysTo()).isZero();
		assertThat(sameDay.isCancelable()).isFalse();
	}

	@Test
	@DisplayName("하루짜리 구간(양끝 포함)과 모든 구간이 취소 불가인 규정도 만들 수 있다")
	void createAllowsOneDayRulesAndAllNotCancelable() {
		saveReturnsArgument();
		CancelPolicySaveCommand command = new CancelPolicySaveCommand("취소 불가 규정",
			List.of(new Rule(0, 0, 0, false), new Rule(1, 1, 0, false), new Rule(2, null, 0, false)));

		CancelPolicyDetail detail = cancelPolicyService.create(7L, command);

		assertThat(detail.rules()).extracting(CancelPolicyRule::getDaysFrom).containsExactly(2, 1, 0);
	}

	@Test
	@DisplayName("제한 없음 구간 하나만 있는 규정(0일 전부터 끝까지)도 만들 수 있다")
	void createAllowsSingleUnlimitedRule() {
		saveReturnsArgument();

		CancelPolicyDetail detail = cancelPolicyService.create(7L, new CancelPolicySaveCommand("한 구간", List.of(rule(0, null))));

		assertThat(detail.rules()).hasSize(1);
	}

	static Stream<Arguments> invalidRules() {
		return Stream.of(
			Arguments.of("끝이 시작보다 작음", List.of(rule(0, 0), rule(3, 1), rule(2, null)), CancelPolicyService.RULE_REVERSED),
			Arguments.of("당일 구간 없음", List.of(rule(1, 2), rule(3, null)), CancelPolicyService.RULE_NOT_FROM_TODAY),
			Arguments.of("제한 없음 구간 없음", List.of(rule(0, 0), rule(1, 6)), CancelPolicyService.RULE_UNLIMITED_NOT_LAST),
			Arguments.of("하루짜리만 있고 제한 없음 없음", List.of(rule(0, 0), rule(1, 1)), CancelPolicyService.RULE_UNLIMITED_NOT_LAST),
			Arguments.of("제한 없음 구간 둘", List.of(rule(0, 0), rule(1, null), rule(7, null)),
				CancelPolicyService.RULE_UNLIMITED_NOT_LAST),
			Arguments.of("제한 없음 구간이 중간", List.of(rule(0, null), rule(3, 6)), CancelPolicyService.RULE_UNLIMITED_NOT_LAST),
			Arguments.of("빈 날짜(3일 전)", List.of(rule(0, 2), rule(4, null)), CancelPolicyService.RULE_GAP_OR_OVERLAP),
			Arguments.of("겹치는 날짜(3일 전)", List.of(rule(0, 3), rule(3, null)), CancelPolicyService.RULE_GAP_OR_OVERLAP),
			Arguments.of("시작 일수 중복", List.of(rule(0, 0), rule(0, 5), rule(6, null)), CancelPolicyService.RULE_GAP_OR_OVERLAP));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRules")
	@DisplayName("구간끼리의 규칙을 어기면 400 PERFORMANCE101 이고 이유는 메시지로 알려 주며, 이름 확인과 저장은 하지 않는다")
	void invalidRulesAreRejectedBeforeNameCheck(String reason, List<Rule> rules, String message) {
		assertThatThrownBy(() -> cancelPolicyService.create(7L, new CancelPolicySaveCommand("내 규정", rules)))
			.isInstanceOf(BusinessException.class)
			.hasMessage(message)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_RULES_INVALID);
		verifyNoInteractions(cancelPolicyRepository, cancelPolicyRuleRepository);
	}

	@Test
	@DisplayName("볼 수 있는 규정 중에 같은 이름이 있으면 409 PERFORMANCE104 이고 저장하지 않는다")
	void duplicatedNameIsConflict() {
		when(cancelPolicyRepository.existsVisibleName(7L, "기본 규정")).thenReturn(true);

		assertThatThrownBy(() -> cancelPolicyService.create(7L,
			new CancelPolicySaveCommand("기본 규정", List.of(rule(0, null)))))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_NAME_DUPLICATED);
		verify(cancelPolicyRepository, never()).save(any());
		verifyNoInteractions(cancelPolicyRuleRepository);
	}

	// ---- 수정 (SLR-22) ----

	private CancelPolicy mine() {
		return CancelPolicy.builder().id(10L).businessId(7L).name("이전 이름").build();
	}

	@Test
	@DisplayName("수정하면 잠근 내 규정의 이름을 바꾸고 기존 구간을 지운 뒤 새 구간을 먼 구간부터 저장하며, 결과는 고칠 수 있는 상태다")
	void updateReplacesNameAndRules() {
		CancelPolicy policy = mine();
		when(cancelPolicyRepository.findMineForUpdate(10L, 7L)).thenReturn(Optional.of(policy));
		when(cancelPolicyRuleRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

		CancelPolicyDetail detail = cancelPolicyService.update(10L, 7L,
			new CancelPolicySaveCommand("새 이름", List.of(rule(0, 2), rule(3, null))));

		assertThat(policy.getName()).isEqualTo("새 이름");
		assertThat(detail.rules()).extracting(CancelPolicyRule::getDaysFrom).containsExactly(3, 0);
		assertThat(detail.editable()).isTrue();
		InOrder inOrder = inOrder(cancelPolicyRepository, performanceRepository, cancelPolicyRuleRepository);
		inOrder.verify(cancelPolicyRepository).findMineForUpdate(10L, 7L);
		inOrder.verify(performanceRepository).existsByCancelPolicyAndStatuses(10L, LOCKING);
		inOrder.verify(cancelPolicyRepository).existsVisibleNameExcept(7L, "새 이름", 10L);
		inOrder.verify(cancelPolicyRuleRepository).deleteByPolicyId(10L);
		inOrder.verify(cancelPolicyRuleRepository).saveAll(anyList());
	}

	@Test
	@DisplayName("수정도 구간끼리의 규칙을 어기면 규정을 잠그거나 조회하기 전에 400 PERFORMANCE101 이다")
	void updateValidatesRulesBeforeLocking() {
		assertThatThrownBy(() -> cancelPolicyService.update(10L, 7L,
			new CancelPolicySaveCommand("새 이름", List.of(rule(1, null)))))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_RULES_INVALID);
		verifyNoInteractions(cancelPolicyRepository, cancelPolicyRuleRepository, performanceRepository);
	}

	@Test
	@DisplayName("잠금 조회에 없는 규정이 기본 규정이면 409 PERFORMANCE102, 없거나 남의 규정이면 404 PERFORMANCE100 이다")
	void updateOfDefaultIsConflictAndOthersIsNotFound() {
		CancelPolicy defaultPolicy = CancelPolicy.builder().id(1L).name("기본 규정").build();
		when(cancelPolicyRepository.findVisible(1L, 7L)).thenReturn(Optional.of(defaultPolicy));
		when(cancelPolicyRepository.findVisible(20L, 7L)).thenReturn(Optional.empty());
		CancelPolicySaveCommand command = new CancelPolicySaveCommand("새 이름", List.of(rule(0, null)));

		assertThatThrownBy(() -> cancelPolicyService.update(1L, 7L, command))
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.DEFAULT_CANCEL_POLICY_NOT_EDITABLE);
		assertThatThrownBy(() -> cancelPolicyService.update(20L, 7L, command))
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_NOT_FOUND);
		verifyNoInteractions(cancelPolicyRuleRepository, performanceRepository);
	}

	@Test
	@DisplayName("공개·취소 공연이 쓰는 규정이면 409 PERFORMANCE103 이고 이름과 구간을 바꾸지 않는다")
	void updateOfPolicyInUseIsConflict() {
		CancelPolicy policy = mine();
		when(cancelPolicyRepository.findMineForUpdate(10L, 7L)).thenReturn(Optional.of(policy));
		when(performanceRepository.existsByCancelPolicyAndStatuses(10L, LOCKING)).thenReturn(true);

		assertThatThrownBy(() -> cancelPolicyService.update(10L, 7L,
			new CancelPolicySaveCommand("새 이름", List.of(rule(0, null)))))
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_IN_USE);
		assertThat(policy.getName()).isEqualTo("이전 이름");
		verifyNoInteractions(cancelPolicyRuleRepository);
	}

	@Test
	@DisplayName("다른 규정과 이름이 겹치면 409 PERFORMANCE104 이고 이름과 구간을 바꾸지 않는다")
	void updateWithDuplicatedNameIsConflict() {
		CancelPolicy policy = mine();
		when(cancelPolicyRepository.findMineForUpdate(10L, 7L)).thenReturn(Optional.of(policy));
		when(cancelPolicyRepository.existsVisibleNameExcept(7L, "기본 규정", 10L)).thenReturn(true);

		assertThatThrownBy(() -> cancelPolicyService.update(10L, 7L,
			new CancelPolicySaveCommand("기본 규정", List.of(rule(0, null)))))
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_NAME_DUPLICATED);
		assertThat(policy.getName()).isEqualTo("이전 이름");
		verifyNoInteractions(cancelPolicyRuleRepository);
	}
}
