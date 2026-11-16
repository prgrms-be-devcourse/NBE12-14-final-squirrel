package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CancelPolicyRuleRepository extends JpaRepository<CancelPolicyRule, Long> {

	/** 규정의 구간들. 저장 순서와 상관없이 공연에서 먼 구간부터(daysFrom 큰 순) 보여 준다. */
	List<CancelPolicyRule> findByPolicyIdOrderByDaysFromDesc(Long policyId);

	/**
	 * 규정 수정(SLR-22)은 구간 전체 교체라 기존 구간을 한 번에 지운다. (구간은 최대 10행)
	 * 앞서 바꾼 이름이 이 DELETE 보다 먼저 반영되도록 실행 전에 flush 한다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM CancelPolicyRule r WHERE r.policy.id = :policyId")
	void deleteByPolicyId(@Param("policyId") Long policyId);
}
