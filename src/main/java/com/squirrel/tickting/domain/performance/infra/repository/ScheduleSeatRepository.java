package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleSeatRepository extends JpaRepository<ScheduleSeat, Long> {

	/** 한 회차 안에서 번호로 좌석을 찾는다. 다른 회차의 좌석은 걸러진다. */
	List<ScheduleSeat> findByScheduleIdAndIdIn(Long scheduleId, Collection<Long> ids);

	/**
	 * 점유할 수 있는(AVAILABLE) 좌석만 HELD 로 바꾸고 바뀐 행 수를 돌려준다.
	 * 상태 조건을 UPDATE 문에 넣었기 때문에 Redis 락이 풀리는 순간이나 Redis 장애 때에도 같은 좌석이 두 번 점유되지 않는다.
	 * 일괄 UPDATE 는 updated_at 을 자동으로 채우지 못해서 직접 넣는다.
	 * 만료된 HELD 는 일부러 건드리지 않는다. 낡은 booking_seat 가 남아 있어 새 점유가 UNIQUE 인덱스와 부딪히므로 스케줄러가 풀어 준 뒤에 잡는다.
	 */
	@Modifying(clearAutomatically = true)
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
	 * 점유 중(HELD)인 좌석만 AVAILABLE 로 되돌리고 되돌린 행 수를 돌려준다.
	 * 상태 조건이 있어서 그 사이 결제가 끝나 SOLD 가 된 좌석은 건드리지 않는다.
	 */
	@Modifying(clearAutomatically = true)
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
}
