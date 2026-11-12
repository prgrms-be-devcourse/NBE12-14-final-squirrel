package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CancelPolicyRepository extends JpaRepository<CancelPolicy, Long> {
}
