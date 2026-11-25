package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import com.squirrel.tickting.domain.performance.type.SeatStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleSeatRepository extends JpaRepository<ScheduleSeat, Long> {

	/**
	 * 한 회차 안에서 번호로 좌석을 찾는다. 다른 회차의 좌석은 걸러진다.
	 * 점유할 좌석을 구역·열·번호(공연장 좌석)와 등급까지 함께 읽는다. 점유한 좌석을 예매에 복사할 때 좌석마다 다시 조회하지 않게 한다.
	 * 공연장 좌석·등급 행이 없는 이상한 데이터도 좌석은 그대로 나오도록 LEFT JOIN 이다.
	 */
	@Query("""
		SELECT s
		FROM ScheduleSeat s
		LEFT JOIN FETCH s.venueSeat vs
		LEFT JOIN FETCH vs.zone
		LEFT JOIN FETCH s.grade
		WHERE s.scheduleId = :scheduleId
		  AND s.id IN :ids
		""")
	List<ScheduleSeat> findWithLocationByScheduleIdAndIdIn(@Param("scheduleId") Long scheduleId, @Param("ids") Collection<Long> ids);

	/**
	 * 점유할 수 있는(AVAILABLE) 좌석만 HELD 로 바꾸고 바뀐 행 수를 돌려준다.
	 * 상태 조건을 UPDATE 문에 넣었기 때문에 Redis 락이 풀리는 순간이나 Redis 장애 때에도 같은 좌석이 두 번 점유되지 않는다.
	 * 일괄 UPDATE 는 updated_at 을 자동으로 채우지 못해서 직접 넣는다.
	 * 만료된 HELD 는 일부러 건드리지 않는다. 낡은 booking_seat 가 남아 있어 새 점유가 UNIQUE 인덱스와 부딪히므로 스케줄러가 풀어 준 뒤에 잡는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE ScheduleSeat s
		   SET s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.HELD,
		       s.heldBy = :userId,
		       s.heldUntil = :heldUntil,
		       s.updatedAt = :now
		 WHERE s.scheduleId = :scheduleId
		   AND s.id IN :seatIds
		   AND s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE
		""")
	int holdAvailable(
		@Param("scheduleId") Long scheduleId,
		@Param("seatIds") Collection<Long> seatIds,
		@Param("userId") Long userId,
		@Param("heldUntil") LocalDateTime heldUntil,
		@Param("now") LocalDateTime now
	);

	/**
	 * 공연의 모든 회차의 판매 좌석을 지운다. 작성 중(DRAFT) 공연의 공연장을 바꿀 때만 쓴다.
	 * 영속성 컨텍스트는 비우지 않는다. 호출한 쪽이 같은 트랜잭션에서 이미 읽어 바꾼 공연 엔티티가 저장되지 않고 사라지기 때문이다.
	 */
	@Modifying(flushAutomatically = true)
	@Query("""
		DELETE FROM ScheduleSeat s
		 WHERE s.scheduleId IN (SELECT ps.id FROM PerformanceSchedule ps WHERE ps.performance.id = :performanceId)
		""")
	int deleteByPerformanceId(@Param("performanceId") Long performanceId);

	/** 점유 중(HELD)인 좌석의 점유 만료 시각만 늘리고 바뀐 행 수를 돌려준다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE ScheduleSeat s
		   SET s.heldUntil = :heldUntil,
		       s.updatedAt = :now
		 WHERE s.id IN :seatIds
		   AND s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.HELD
		""")
	int extendHold(@Param("seatIds") Collection<Long> seatIds, @Param("heldUntil") LocalDateTime heldUntil,
		@Param("now") LocalDateTime now);

	/**
	 * 점유 중(HELD)인 좌석만 판매 완료(SOLD)로 바꾸고 바뀐 행 수를 돌려준다. 점유 정보(held_by, held_until)는 지운다.
	 * 상태 조건이 있어서 그 사이 풀려 다른 상태가 된 좌석은 건드리지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE ScheduleSeat s
		   SET s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.SOLD,
		       s.heldBy = NULL,
		       s.heldUntil = NULL,
		       s.updatedAt = :now
		 WHERE s.id IN :seatIds
		   AND s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.HELD
		""")
	int markSold(@Param("seatIds") Collection<Long> seatIds, @Param("now") LocalDateTime now);

	/**
	 * 점유 중(HELD)인 좌석만 AVAILABLE 로 되돌리고 되돌린 행 수를 돌려준다.
	 * 상태 조건이 있어서 그 사이 결제가 끝나 SOLD 가 된 좌석은 건드리지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE ScheduleSeat s
		   SET s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE,
		       s.heldBy = NULL,
		       s.heldUntil = NULL,
		       s.updatedAt = :now
		 WHERE s.id IN :seatIds
		   AND s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.HELD
		""")
	int releaseHeld(@Param("seatIds") Collection<Long> seatIds, @Param("now") LocalDateTime now);

	/**
	 * 판매 완료(SOLD)된 좌석만 AVAILABLE 로 되돌리고 되돌린 행 수를 돌려준다. 예매 취소로 좌석을 다시 팔 때 쓴다.
	 * 상태 조건이 있어서 그 사이 다른 상태가 된 좌석(판매자 보류 등)은 건드리지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE ScheduleSeat s
		   SET s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE,
		       s.updatedAt = :now
		 WHERE s.id IN :seatIds
		   AND s.status = com.squirrel.tickting.domain.performance.type.SeatStatus.SOLD
		""")
	int releaseSold(@Param("seatIds") Collection<Long> seatIds, @Param("now") LocalDateTime now);

	/**
	 * 공연장 좌석을 복사해 한 회차의 판매 좌석을 만들고 만든 행 수를 돌려준다. 한 문장으로 만들어 왕복을 한 번으로 한다.
	 * 판매(ASSIGNED) 구역은 AVAILABLE 로 등급·가격을 복사하고, 그 밖의 구역(의도적 미판매)은 등급·가격 없이 BLOCKED 로 만든다.
	 * 네이티브라 감사(@LastModifiedDate)가 걸리지 않아 version 은 0, updated_at 은 :now 를 직접 넣는다. (둘 다 NOT NULL)
	 * 같은 회차를 두 번 만들면 UNIQUE (schedule_id, venue_seat_id) 가 막는다.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
		INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, grade_id, price, status, version, updated_at)
		SELECT :scheduleId, vs.id, vs.zone_id, g.id, g.price,
		       CASE WHEN zg.assignment_status = 'ASSIGNED' THEN 'AVAILABLE' ELSE 'BLOCKED' END,
		       0, :now
		  FROM venue_seat vs
		  JOIN zone_grade zg ON zg.zone_id = vs.zone_id AND zg.performance_id = :performanceId
		  LEFT JOIN seat_grade g ON g.id = zg.grade_id AND zg.assignment_status = 'ASSIGNED'
		 WHERE vs.venue_id = :venueId
		""", nativeQuery = true)
	int copyFromVenueSeats(@Param("scheduleId") Long scheduleId, @Param("performanceId") Long performanceId,
		@Param("venueId") Long venueId, @Param("now") LocalDateTime now);

	/**
	 * 회차별로 판매 좌석을 상태별로 센다. 판매자 판매 현황(SLR-17)이 쓴다.
	 * 만료됐지만 아직 정리되지 않은 HELD 도 그대로 센다. 예매자도 그 좌석을 고를 수 없어서, 판매 가능 수가 예매자가 고를 수 있는 좌석과 같아진다.
	 * 좌석이 없는 회차나 개수가 0인 상태는 행이 나오지 않는다.
	 */
	@Query("""
		SELECT s.scheduleId AS scheduleId, s.status AS status, COUNT(s) AS count
		FROM ScheduleSeat s
		WHERE s.scheduleId IN :scheduleIds
		GROUP BY s.scheduleId, s.status
		""")
	List<SeatStatusCount> countByStatus(@Param("scheduleIds") Collection<Long> scheduleIds);

	/** {@link #countByStatus} 의 한 행. 별칭과 같은 이름의 getter 로 받는다. */
	interface SeatStatusCount {

		Long getScheduleId();

		SeatStatus getStatus();

		Long getCount();
	}
}
