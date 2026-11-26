package com.squirrel.tickting.domain.booking.infra.repository;

import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {

	List<BookingSeat> findByBookingIdOrderById(Long bookingId);

	long countByBookingId(Long bookingId);

	/**
	 * 한 사람이 한 공연에서 가진 활성 좌석 수. 1인 최대 매수 검사에 쓴다.
	 * 결제 대기(PENDING)와 결제 완료 좌석이 들어가고, 취소·만료되어 active=false 가 된 좌석은 빠진다.
	 * 카운터 컬럼을 따로 두지 않고 실제 행을 센다. (취소·만료 때마다 카운터를 맞추다 어긋나는 일을 피한다)
	 */
	@Query("""
		SELECT COUNT(bs)
		  FROM BookingSeat bs, Booking b
		 WHERE bs.bookingId = b.id
		   AND b.userId = :userId
		   AND b.performanceId = :performanceId
		   AND bs.active = true
		""")
	long countActiveByUserAndPerformance(@Param("userId") Long userId, @Param("performanceId") Long performanceId);

	/** 한 사람이 한 공연에서 가진 활성 좌석 중 예매 상태가 주어진 것의 수. 결제한 좌석만 따로 셀 때 쓴다. */
	@Query("""
		SELECT COUNT(bs)
		  FROM BookingSeat bs, Booking b
		 WHERE bs.bookingId = b.id
		   AND b.userId = :userId
		   AND b.performanceId = :performanceId
		   AND b.status IN :statuses
		   AND bs.active = true
		""")
	long countActiveByUserAndPerformanceAndStatusIn(@Param("userId") Long userId,
		@Param("performanceId") Long performanceId, @Param("statuses") Collection<BookingStatus> statuses);

	/** 예매들의 활성 좌석에 걸린 판매 좌석 번호. 만료된 예매의 좌석을 되돌릴 때 쓴다. */
	@Query("""
		SELECT bs.scheduleSeatId
		  FROM BookingSeat bs
		 WHERE bs.bookingId IN :bookingIds
		   AND bs.active = true
		   AND bs.scheduleSeatId IS NOT NULL
		""")
	List<Long> findActiveScheduleSeatIds(@Param("bookingIds") Collection<Long> bookingIds);

	/**
	 * 예매들의 좌석을 비활성으로 바꾼다. active=false 가 되어야 그 좌석이 다시 팔릴 수 있다. (활성 좌석에만 UNIQUE 인덱스가 걸려 있다)
	 * 취소가 아니라 만료라서 canceled_at 은 채우지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE BookingSeat bs SET bs.active = false WHERE bs.bookingId IN :bookingIds AND bs.active = true")
	int deactivateByBookingIds(@Param("bookingIds") Collection<Long> bookingIds);

	/**
	 * 좌석 한 장을 취소한다. 그 예매의 활성 좌석일 때만 바꾸고 바뀐 행 수를 돌려준다.
	 * 예매 단위 락이 같은 좌석의 동시 취소를 막지만, 락이 뚫려도 active 조건 때문에 한 번만 취소되어 이중 환불이 생기지 않는다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE BookingSeat bs
		   SET bs.active = false,
		       bs.canceledAt = :now,
		       bs.cancelFee = :cancelFee,
		       bs.refundId = :refundId
		 WHERE bs.id = :id
		   AND bs.bookingId = :bookingId
		   AND bs.active = true
		""")
	int cancel(@Param("id") Long id, @Param("bookingId") Long bookingId, @Param("cancelFee") int cancelFee,
		@Param("refundId") Long refundId, @Param("now") LocalDateTime now);
}
