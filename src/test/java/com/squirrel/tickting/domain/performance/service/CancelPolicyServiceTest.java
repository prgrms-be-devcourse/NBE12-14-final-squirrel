package com.squirrel.tickting.domain.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.domain.performance.service.CancelPolicyService.SaveCommand.Rule;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * "기본 규정 또는 내 것"이라는 조건과 이름 비교(대소문자 무시)는 쿼리에 있어서 통합 테스트가 확인한다.
 * 여기서는 구간 관계 검사와 생성 흐름(검사 순서, 저장 순서)을 본다.
 */
@ExtendWith(MockitoExtension.class)
class CancelPolicyServiceTest {

	@Mock
	private CancelPolicyRepository cancelPolicyRepository;
	@Mock
	private CancelPolicyRuleRepository cancelPolicyRuleRepository;

	@InjectMocks
	private CancelPolicyService cancelPolicyService;

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
	@DisplayName("상세는 볼 수 있는 규정과 그 규정의 구간을 함께 돌려준다")
	void detailReturnsPolicyWithRules() {
		CancelPolicy mine = CancelPolicy.builder().id(10L).businessId(7L).name("내 규정").build();
		List<CancelPolicyRule> rules = List.of(
			CancelPolicyRule.builder().policy(mine).daysFrom(1).feeRate(0).cancelable(true).build(),
			CancelPolicyRule.builder().policy(mine).daysFrom(0).daysTo(0).feeRate(0).cancelable(false).build());
		when(cancelPolicyRepository.findVisible(10L, 7L)).thenReturn(Optional.of(mine));
		when(cancelPolicyRuleRepository.findByPolicyIdOrderByDaysFromDesc(10L)).thenReturn(rules);

		CancelPolicyService.Detail detail = cancelPolicyService.getVisibleDetail(10L, 7L);

		assertThat(detail.policy()).isSameAs(mine);
		assertThat(detail.rules()).isSameAs(rules);
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
		CancelPolicyService.SaveCommand command = new CancelPolicyService.SaveCommand("내 규정",
			List.of(rule(1, 2), new Rule(7, null, 0, true), new Rule(0, 0, 0, false), new Rule(3, 6, 10, true)));

		CancelPolicyService.Detail detail = cancelPolicyService.create(7L, command);

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
		CancelPolicyService.SaveCommand command = new CancelPolicyService.SaveCommand("취소 불가 규정",
			List.of(new Rule(0, 0, 0, false), new Rule(1, 1, 0, false), new Rule(2, null, 0, false)));

		CancelPolicyService.Detail detail = cancelPolicyService.create(7L, command);

		assertThat(detail.rules()).extracting(CancelPolicyRule::getDaysFrom).containsExactly(2, 1, 0);
	}

	@Test
	@DisplayName("제한 없음 구간 하나만 있는 규정(0일 전부터 끝까지)도 만들 수 있다")
	void createAllowsSingleUnlimitedRule() {
		saveReturnsArgument();

		CancelPolicyService.Detail detail = cancelPolicyService.create(7L, new CancelPolicyService.SaveCommand("한 구간", List.of(rule(0, null))));

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
		assertThatThrownBy(() -> cancelPolicyService.create(7L, new CancelPolicyService.SaveCommand("내 규정", rules)))
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
			new CancelPolicyService.SaveCommand("기본 규정", List.of(rule(0, null)))))
			.isInstanceOf(BusinessException.class)
			.extracting("exceptionCode").isEqualTo(PerformanceExceptionCode.CANCEL_POLICY_NAME_DUPLICATED);
		verify(cancelPolicyRepository, never()).save(any());
		verifyNoInteractions(cancelPolicyRuleRepository);
	}
}
