package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {

	/** 이 사업체의 공연에 속한 회차인가. 남의 회차와 없는 회차를 구분하지 않으려고 존재 여부만 묻는다. */
	boolean existsByIdAndPerformanceBusinessId(Long id, Long businessId);

	/** 공연의 모든 회차. 취소·연기된 회차도 포함하고 공연 일시가 빠른 순이다. */
	List<PerformanceSchedule> findByPerformanceIdOrderByStartAt(Long performanceId);

	/** 제외한 상태가 아닌 회차. 공연 일시가 빠른 순이다. 공개 조건처럼 취소된 회차를 빼고 볼 때 쓴다. */
	List<PerformanceSchedule> findByPerformanceIdAndStatusNotOrderByStartAt(Long performanceId,
		ScheduleStatus excludedStatus);

	/**
	 * 제외한 상태가 아닌 회차의 판매 좌석 생성 시각을 비워 스케줄러가 다시 만들게 한다. 공연장을 바꾸면서 좌석을 지울 때 쓴다.
	 * 취소 회차는 비우지 않는다. 끝난 회차의 좌석을 지워도 값이 남아 다시 만들지 않는 것과 같게, 취소 회차의 좌석이 되살아나지 않게 한다.
	 * 일괄 UPDATE 는 updated_at 을 자동으로 채우지 못해 직접 넣고, 영속성 컨텍스트는 비우지 않는다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE PerformanceSchedule s
		   SET s.seatsGeneratedAt = NULL,
		       s.updatedAt = :now
		 WHERE s.performance.id = :performanceId
		   AND s.status <> :excludedStatus
		""")
	int clearSeatsGenerated(@Param("performanceId") Long performanceId,
		@Param("excludedStatus") ScheduleStatus excludedStatus, @Param("now") LocalDateTime now);

	/**
	 * 이 공연의 회차 중, 주어진 공연장의 다른 회차와 시간이 겹치는 회차의 시작 일시. 빠른 순이다.
	 * 회차가 차지하는 시간은 시작부터 러닝타임 + bufferMinutes 까지이고, 끝나는 시각과 다음 시작이 같으면 겹치지 않는다.
	 * 이 공연의 회차는 새 러닝타임(runningTime)으로, 다른 공연의 회차는 그 공연의 러닝타임으로 계산한다.
	 * 세지 않는 회차: 취소 회차, 취소된 공연의 회차, 새 일정이 정해지지 않은(가장 최근 연기 이력의 new_start_at 이 비어 있는) 연기 회차.
	 * 대상은 그 공연장을 쓰는 공연의 회차라서, 공연장을 바꿀 때는 이 공연의 회차끼리는 비교되지 않고(옛 공연장 소속),
	 * 러닝타임을 늘릴 때는 같은 공연의 다른 회차와 비교된다. 회차마다 묻지 않고 쿼리 한 번으로 모두 본다.
	 * JPQL 로는 러닝타임(분)만큼 시각을 더하기 어려워 네이티브 쿼리다.
	 */
	@Query(value = """
		WITH occupying AS (
		    SELECT s.id, s.performance_id, s.start_at, p.venue_id,
		           CASE WHEN p.id = :performanceId THEN CAST(:runningTime AS integer) ELSE p.running_time END AS running_time
		      FROM performance_schedule s
		      JOIN performance p ON p.id = s.performance_id
		     WHERE s.status <> 'CANCELED'
		       AND p.status <> 'CANCELED'
		       AND NOT (s.status = 'POSTPONED' AND (
		             SELECT sp.new_start_at FROM schedule_postponement sp
		              WHERE sp.schedule_id = s.id
		              ORDER BY sp.created_at DESC, sp.id DESC
		              LIMIT 1) IS NULL)
		       AND (p.id = :performanceId OR p.venue_id = :venueId)
		)
		SELECT DISTINCT mine.start_at
		  FROM occupying mine
		  JOIN occupying other ON other.id <> mine.id AND other.venue_id = :venueId
		 WHERE mine.performance_id = :performanceId
		   AND mine.start_at < other.start_at + make_interval(mins => other.running_time + CAST(:bufferMinutes AS integer))
		   AND other.start_at < mine.start_at + make_interval(mins => mine.running_time + CAST(:bufferMinutes AS integer))
		 ORDER BY mine.start_at
		""", nativeQuery = true)
	List<LocalDateTime> findOverlappingStartAts(@Param("performanceId") Long performanceId,
		@Param("venueId") Long venueId, @Param("runningTime") int runningTime,
		@Param("bufferMinutes") int bufferMinutes);

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
