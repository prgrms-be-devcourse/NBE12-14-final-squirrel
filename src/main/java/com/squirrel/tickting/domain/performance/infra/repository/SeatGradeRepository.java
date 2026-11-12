package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {
}
