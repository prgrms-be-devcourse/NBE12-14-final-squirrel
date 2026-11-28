package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {

	/** 이 사업체의 공연에 속한 회차인가. 남의 회차와 없는 회차를 구분하지 않으려고 존재 여부만 묻는다. */
	boolean existsByIdAndPerformanceBusinessId(Long id, Long businessId);

	/**
	 * 회차와 그 공연을 한 번에 읽는다. 예매할 수 있는지 확인할 때 공연을 지연 로딩으로 따로 읽으면 왕복이 하나 더 든다.
	 * 점유는 락 안에서 이 쿼리를 다시 부르므로 왕복이 곧 락을 쥐는 시간이다.
	 */
	@Query("SELECT s FROM PerformanceSchedule s JOIN FETCH s.performance WHERE s.id = :scheduleId")
	Optional<PerformanceSchedule> findWithPerformanceById(@Param("scheduleId") Long scheduleId);

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
	 * 판매 좌석을 아직 만들지 않은 회차만 생성 시각을 채우고 바뀐 행 수를 돌려준다. 0행이면 이미 만들어졌거나 그 사이 취소됐다.
	 * 락이 듣지 않는 경우(Redis 장애·락 만료)에도 같은 회차의 좌석이 두 번 만들어지지 않게 하는 상태 조건부 UPDATE 다.
	 * 일괄 UPDATE 는 updated_at 을 자동으로 채우지 못해 직접 넣고, 영속성 컨텍스트는 비우지 않는다.
	 * 이 UPDATE 뒤에 같은 엔티티를 다시 읽어 값을 기대하지 않는다. (응답의 시각은 호출한 쪽이 now 를 쓴다)
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE PerformanceSchedule s
		   SET s.seatsGeneratedAt = :now,
		       s.updatedAt = :now
		 WHERE s.id = :scheduleId
		   AND s.seatsGeneratedAt IS NULL
		   AND s.status <> :excludedStatus
		""")
	int markSeatsGenerated(@Param("scheduleId") Long scheduleId,
		@Param("excludedStatus") ScheduleStatus excludedStatus, @Param("now") LocalDateTime now);

	/**
	 * 바꿀 수 있는 상태이고 아직 시작하지 않은 회차만 공연 일시를 바꾸고 바뀐 행 수를 돌려준다. 0행이면 확인한 뒤에 상태나 일시가 바뀌었다.
	 * 락이 듣지 않는 경우(Redis 장애·락 만료)에도 취소·연기되거나 시작한 회차의 일시가 바뀌지 않게 하는 상태 조건부 UPDATE 다.
	 * 일괄 UPDATE 는 updated_at 을 자동으로 채우지 못해 직접 넣고, 영속성 컨텍스트는 비우지 않는다.
	 * 이 UPDATE 뒤에 같은 엔티티를 다시 읽어 값을 기대하지 않는다. (응답의 값은 호출한 쪽이 넘긴 값을 쓴다)
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE PerformanceSchedule s
		   SET s.startAt = :startAt,
		       s.updatedAt = :now
		 WHERE s.id = :scheduleId
		   AND s.status IN :editableStatuses
		   AND s.startAt > :now
		""")
	int updateStartAt(@Param("scheduleId") Long scheduleId, @Param("startAt") LocalDateTime startAt,
		@Param("editableStatuses") Collection<ScheduleStatus> editableStatuses, @Param("now") LocalDateTime now);

	/**
	 * 상태가 fromStatus 이고 아직 시작하지 않은 회차만 판매 상태와 중지 사유를 바꾸고 바뀐 행 수를 돌려준다. (일시 중지·재개·일정 미정 연기)
	 * 락이 듣지 않는 경우(Redis 장애·락 만료)에도 그 사이 바뀌거나 시작한 회차를 덮어쓰지 않게 하는 상태 조건부 UPDATE 다.
	 * pauseReason 은 중지하면 사유, 재개·연기하면 null 이다.
	 * 일괄 UPDATE 는 updated_at 을 자동으로 채우지 못해 직접 넣고, 영속성 컨텍스트는 비우지 않는다. (읽어 둔 엔티티에는 바꾸기 전 값이 남는다)
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE PerformanceSchedule s
		   SET s.status = :toStatus,
		       s.pauseReason = :pauseReason,
		       s.updatedAt = :now
		 WHERE s.id IN :scheduleIds
		   AND s.status = :fromStatus
		   AND s.startAt > :now
		""")
	int updateSaleStatus(@Param("scheduleIds") Collection<Long> scheduleIds,
		@Param("fromStatus") ScheduleStatus fromStatus, @Param("toStatus") ScheduleStatus toStatus,
		@Param("pauseReason") String pauseReason, @Param("now") LocalDateTime now);

	/**
	 * 일정 미정으로 연기한 회차만 새 일시를 넣고 판매 중으로 바꾼 뒤 바뀐 행 수를 돌려준다. 0행이면 확인한 뒤에 상태가 바뀌었다.
	 * 원래 일시가 지났을 수 있어 시작 시각은 보지 않는다. 락이 듣지 않는 경우의 방어선인 상태 조건부 UPDATE 다.
	 * 일괄 UPDATE 는 updated_at 을 자동으로 채우지 못해 직접 넣고, 영속성 컨텍스트는 비우지 않는다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
		UPDATE PerformanceSchedule s
		   SET s.startAt = :startAt,
		       s.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.ON_SALE,
		       s.updatedAt = :now
		 WHERE s.id = :scheduleId
		   AND s.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.POSTPONED
		""")
	int confirmPostponed(@Param("scheduleId") Long scheduleId, @Param("startAt") LocalDateTime startAt,
		@Param("now") LocalDateTime now);

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
	 * 저장하려는 시간(venueId 공연장, startAt 시작, runningTime 분)과 겹치는 기존 회차. 시작 일시가 빠른 순이다.
	 * 일시를 바꾸는 회차는 자기 자신과 겹치지 않게 excludedScheduleId 로 뺀다. 새로 추가하는 회차는 null 이다.
	 * 겹침 기준과 세지 않는 회차는 findOverlappingStartAts 와 같다. 한쪽만 바꾸면 회차 추가·수정과 공연 수정의 판단이 어긋난다.
	 * 내 회차인지 구분해 알려 줄 수 있게 사업체 번호·공연명·러닝타임을 함께 읽는다.
	 */
	@Query(value = """
		SELECT p.business_id AS "businessId", p.title AS "title", s.start_at AS "startAt",
		       p.running_time AS "runningTime"
		  FROM performance_schedule s
		  JOIN performance p ON p.id = s.performance_id
		 WHERE p.venue_id = :venueId
		   AND s.status <> 'CANCELED'
		   AND p.status <> 'CANCELED'
		   AND s.id IS DISTINCT FROM CAST(:excludedScheduleId AS bigint)
		   AND NOT (s.status = 'POSTPONED' AND (
		         SELECT sp.new_start_at FROM schedule_postponement sp
		          WHERE sp.schedule_id = s.id
		          ORDER BY sp.created_at DESC, sp.id DESC
		          LIMIT 1) IS NULL)
		   AND CAST(:startAt AS timestamp) < s.start_at + make_interval(mins => p.running_time + CAST(:bufferMinutes AS integer))
		   AND s.start_at < CAST(:startAt AS timestamp)
		       + make_interval(mins => CAST(:runningTime AS integer) + CAST(:bufferMinutes AS integer))
		 ORDER BY s.start_at, s.id
		""", nativeQuery = true)
	List<OverlappingSchedule> findOverlappingSchedules(@Param("venueId") Long venueId,
		@Param("startAt") LocalDateTime startAt, @Param("runningTime") int runningTime,
		@Param("bufferMinutes") int bufferMinutes, @Param("excludedScheduleId") Long excludedScheduleId);

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

	/**
	 * 판매 좌석을 자동으로 만들 회차. 공개 상태 공연의 취소되지 않고 아직 시작하지 않은 회차 중, 좌석이 없고 판매 오픈이 openUntil 이하인 것이다.
	 * 일시 중지·연기 회차도 포함하고 판매 종료는 보지 않는다. 다시 팔기로 하면 바로 예매할 수 있게 하려는 것이다.
	 * 판매 오픈이 빠른 순이고 같으면 회차 번호순이다. 오픈이 가까운 회차부터 만들어야 한다.
	 */
	@Query("""
		SELECT s.id AS scheduleId, p.id AS performanceId
		  FROM PerformanceSchedule s
		  JOIN s.performance p
		 WHERE p.status = :performanceStatus
		   AND s.status <> :excludedStatus
		   AND s.startAt > :now
		   AND s.seatsGeneratedAt IS NULL
		   AND p.saleOpenAt <= :openUntil
		 ORDER BY p.saleOpenAt, s.id
		""")
	List<SeatGenerationTarget> findSeatGenerationTargets(@Param("performanceStatus") PerformanceStatus performanceStatus,
		@Param("excludedStatus") ScheduleStatus excludedStatus, @Param("now") LocalDateTime now,
		@Param("openUntil") LocalDateTime openUntil);

	/** findSeatGenerationTargets 의 결과 한 줄. */
	interface SeatGenerationTarget {

		Long getScheduleId();

		Long getPerformanceId();
	}

	/** findOverlappingSchedules 의 결과 한 줄. */
	interface OverlappingSchedule {

		Long getBusinessId();

		String getTitle();

		LocalDateTime getStartAt();

		Integer getRunningTime();
	}
}
