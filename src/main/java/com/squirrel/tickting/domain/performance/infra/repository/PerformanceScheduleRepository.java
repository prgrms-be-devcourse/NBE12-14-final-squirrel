package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {

	/** 공연의 모든 회차. 취소·연기된 회차도 포함하고 공연 일시가 빠른 순이다. */
	List<PerformanceSchedule> findByPerformanceIdOrderByStartAt(Long performanceId);

	/** 공연별 회차 수. 취소·연기된 회차도 센다. 회차가 없는 공연은 결과에 나오지 않는다. */
	@Query("""
		SELECT s.performance.id AS performanceId, COUNT(s) AS scheduleCount
		FROM PerformanceSchedule s
		WHERE s.performance.id IN :performanceIds
		GROUP BY s.performance.id
		""")
	List<ScheduleCount> countByPerformanceIds(@Param("performanceIds") Collection<Long> performanceIds);

	interface ScheduleCount {

		Long getPerformanceId();

		long getScheduleCount();
	}
}
