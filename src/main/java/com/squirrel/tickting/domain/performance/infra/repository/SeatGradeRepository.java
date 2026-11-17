package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatGradeRepository extends JpaRepository<SeatGrade, Long> {

	/** 공연의 모든 등급. 예매자 화면(findAssignedGrades)과 같이 비싼 등급부터, 가격이 같으면 번호순이다. */
	List<SeatGrade> findByPerformanceIdOrderByPriceDescIdAsc(Long performanceId);
}
