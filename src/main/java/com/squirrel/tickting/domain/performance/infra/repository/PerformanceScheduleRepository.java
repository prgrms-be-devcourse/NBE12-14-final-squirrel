package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {

	/** 공연의 모든 회차. 취소·연기된 회차도 포함하고 공연 일시가 빠른 순이다. */
	List<PerformanceSchedule> findByPerformanceIdOrderByStartAt(Long performanceId);

	/** 제외한 상태가 아닌 회차. 공연 일시가 빠른 순이다. 공개 조건처럼 취소된 회차를 빼고 볼 때 쓴다. */
	List<PerformanceSchedule> findByPerformanceIdAndStatusNotOrderByStartAt(Long performanceId,
		ScheduleStatus excludedStatus);

	/**
	 * 제외한 상태가 아닌 회차 중 판매 좌석을 이미 만든 회차가 있는가. 등급·가격 수정 잠금(locked) 판단에 쓴다.
	 * 취소 회차를 빼는 이유: 그 좌석은 더 팔리지 않고 환불은 예매 때 저장한 가격으로 해서, 세면 나머지 회차의 가격만 막힌다.
	 */
	@Query("""
		SELECT COUNT(s) > 0
		FROM PerformanceSchedule s
		WHERE s.performance.id = :performanceId
		  AND s.status <> :excludedStatus
		  AND s.seatsGeneratedAt IS NOT NULL
		""")
	boolean existsSeatsGenerated(@Param("performanceId") Long performanceId,
		@Param("excludedStatus") ScheduleStatus excludedStatus);
}
