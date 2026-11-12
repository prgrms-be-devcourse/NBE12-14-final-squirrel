package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancelPolicyRuleRepository extends JpaRepository<CancelPolicyRule, Long> {
}
