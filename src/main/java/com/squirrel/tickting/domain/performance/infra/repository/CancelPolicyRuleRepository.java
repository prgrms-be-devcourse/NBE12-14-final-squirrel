package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancelPolicyRuleRepository extends JpaRepository<CancelPolicyRule, Long> {

	/** 규정의 구간들. 저장 순서와 상관없이 공연에서 먼 구간부터(daysFrom 큰 순) 보여 준다. */
	List<CancelPolicyRule> findByPolicyIdOrderByDaysFromDesc(Long policyId);
}
