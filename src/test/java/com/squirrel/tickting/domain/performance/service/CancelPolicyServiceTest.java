package com.squirrel.tickting.domain.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** "기본 규정 또는 내 것"이라는 조건 자체는 쿼리에 있어서 통합 테스트(SellerPerformanceCreateApiTest)가 확인한다. */
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

		CancelPolicyDetail detail = cancelPolicyService.getVisibleDetail(10L, 7L);

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
}
