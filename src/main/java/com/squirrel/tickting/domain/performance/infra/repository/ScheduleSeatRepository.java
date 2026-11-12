package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleSeatRepository extends JpaRepository<ScheduleSeat, Long> {
}
