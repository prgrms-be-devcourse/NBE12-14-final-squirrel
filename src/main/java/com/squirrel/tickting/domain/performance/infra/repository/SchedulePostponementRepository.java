package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.SchedulePostponement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulePostponementRepository extends JpaRepository<SchedulePostponement, Long> {
}
