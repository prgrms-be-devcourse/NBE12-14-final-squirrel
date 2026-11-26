package com.squirrel.tickting.domain.booking.infra.repository;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

	/** 내 예매만 찾는다. 남의 예매 번호로 조회하면 비어 있다. (번호가 존재하는지 알려주지 않으려고 호출한 쪽이 404 로 답한다) */
	Optional<Booking> findByIdAndUserId(Long id, Long userId);

	/** 그 공연에 주어진 상태의 예매가 하나라도 있는가. performance_id 는 예매 때 복사해 둔 값이라 조인 없이 본다. */
	boolean existsByPerformanceIdAndStatusIn(Long performanceId, Collection<BookingStatus> statuses);

	/** 그 회차에 주어진 상태가 아닌 예매가 하나라도 있는가. */
	boolean existsByScheduleIdAndStatusNot(Long scheduleId, BookingStatus status);

	/**
	 * 판매자의 회차별 예매 목록. 예매 시각 최신순, 같으면 번호가 큰 순으로 고정한다(페이지를 넘겨도 순서가 바뀌지 않게).
	 * PostgreSQL 에서 null 파라미터가 타입 오류를 내서 선택 조건은 null 대신 "전부 넘기기"로 쓴다:
	 * 상태·수령 방법은 허용할 값 목록을 항상 받고, 예매 번호는 빈 문자열이면 거르지 않는다. 예매 번호는 정확 일치다.
	 *
	 * @deprecated 역할별 예매 조회를 BookingQueryService 로 모았다. 새 코드는 BookingQueryRepository.searchOfBusiness 를 쓴다. 이미 쓰는 곳이 있어서 지우지 않고 남겨 둔다.
	 */
	@Deprecated(since = "2026-11-25")
	@Query(value = """
		SELECT b
		  FROM Booking b
		 WHERE b.scheduleId = :scheduleId
		   AND b.status IN :statuses
		   AND b.receiveType IN :receiveTypes
		   AND (:bookingNo = '' OR b.bookingNo = :bookingNo)
		 ORDER BY b.createdAt DESC, b.id DESC
		""",
		countQuery = """
			SELECT COUNT(b)
			  FROM Booking b
			 WHERE b.scheduleId = :scheduleId
			   AND b.status IN :statuses
			   AND b.receiveType IN :receiveTypes
			   AND (:bookingNo = '' OR b.bookingNo = :bookingNo)
			""")
	Page<Booking> findBySchedule(@Param("scheduleId") Long scheduleId,
		@Param("statuses") Collection<BookingStatus> statuses,
		@Param("receiveTypes") Collection<ReceiveMethod> receiveTypes,
		@Param("bookingNo") String bookingNo, Pageable pageable);

	/**
	 * 결제 제한 시각이 지난 결제 대기(PENDING) 예매를 잠그고 번호를 돌려준다. 가장 오래된 것부터 limit 건까지다.
	 * 서버가 여러 대여도 같은 예매를 두 번 처리하지 않게 이미 다른 곳에서 잠근 행은 기다리지 않고 건너뛴다. (SKIP LOCKED)
	 * 잠금은 호출한 트랜잭션이 끝날 때까지 이어지고, 결제 승인이 같은 예매를 바꾸는 동안에도 건너뛰므로 둘이 겹치지 않는다.
	 * JPQL 로는 SKIP LOCKED 를 확실하게 쓸 수 없어서 네이티브 쿼리다. idx_booking_pending_expires 를 탄다.
	 */
	@Query(value = """
		SELECT id FROM booking
		 WHERE status = 'PENDING' AND expires_at < :now
		 ORDER BY expires_at
		 LIMIT :limit
		 FOR UPDATE SKIP LOCKED
		""", nativeQuery = true)
	List<Long> lockOverdueIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

	/**
	 * 결제 완료 처리. 결제 대기(PENDING)인 예매만 PAID 로 바꾸고 바뀐 행 수를 돌려준다.
	 * 일괄 UPDATE 는 영속성 컨텍스트를 비우는데(clearAutomatically), 그 전에 flush 하지 않으면 같은 트랜잭션에서 엔티티로 바꿔 둔
	 * 값(예: 결제 DONE)이 DB 에 반영되기 전에 사라진다. 그래서 이 저장소의 일괄 UPDATE 는 모두 flushAutomatically 도 켠다.
	 * 상태 조건이 있어서 그 사이 만료 처리된 예매는 건드리지 않는다. (0 이면 호출한 쪽이 어긋난 상태로 보고 처리한다)
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Booking b
		   SET b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		       b.updatedAt = :now
		 WHERE b.id = :id
		   AND b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.PENDING
		""")
	int markPaid(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** 만료 처리. 잠근 예매의 상태를 PENDING 에서 EXPIRED 로 바꾸고 바뀐 행 수를 돌려준다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Booking b
		   SET b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.EXPIRED,
		       b.updatedAt = :now
		 WHERE b.id IN :ids
		   AND b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.PENDING
		""")
	int markExpired(@Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

	/**
	 * 만료된 예매를 취소로 바꾼다. 만료(EXPIRED)된 예매만 바꾸고 바뀐 행 수를 돌려준다. 결제 불일치 보정이 "예매는 만료됐는데
	 * 토스에서는 결제된" 건을 환불할 때 쓴다. 결제된 예매가 만료로 남아 있으면 사용자가 돈을 낸 기록과 어긋난다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Booking b
		   SET b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.CANCELED,
		       b.canceledAt = :now,
		       b.updatedAt = :now
		 WHERE b.id = :id
		   AND b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.EXPIRED
		""")
	int markExpiredAsCanceled(@Param("id") Long id, @Param("now") LocalDateTime now);

	/**
	 * 예매자의 일부 취소 처리. 결제 완료·일부 취소인 예매만 바꾸고 바뀐 행 수를 돌려준다.
	 * 회차가 취소되지 않았을 때만 바꾼다. 취소 회차의 환불은 따로 만들어져서, 예매 락이 듣지 않아도 직접 취소가 뒤늦게 반영되지 않게 한다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Booking b
		   SET b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED,
		       b.updatedAt = :now
		 WHERE b.id = :id
		   AND b.status IN (com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		                    com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED)
		   AND NOT EXISTS (SELECT s.id FROM PerformanceSchedule s
		                    WHERE s.id = b.scheduleId
		                      AND s.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED)
		""")
	int markPartiallyCanceled(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** 예매자의 전체 취소 처리. 결제 완료·일부 취소이고 회차가 취소되지 않은 예매만 바꾸고 전체 취소 일시를 남긴다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Booking b
		   SET b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.CANCELED,
		       b.canceledAt = :now,
		       b.updatedAt = :now
		 WHERE b.id = :id
		   AND b.status IN (com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		                    com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED)
		   AND NOT EXISTS (SELECT s.id FROM PerformanceSchedule s
		                    WHERE s.id = b.scheduleId
		                      AND s.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED)
		""")
	int markCanceled(@Param("id") Long id, @Param("now") LocalDateTime now);

	/**
	 * 회차 취소 환불이 끝난 예매를 전체 취소로 바꾼다. 취소 회차의 예매를 바꾸는 것이라 markCanceled 와 달리 회차 조건이 없다.
	 * 결제 완료·일부 취소인 예매만 바꾸고 바뀐 행 수를 돌려준다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Booking b
		   SET b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.CANCELED,
		       b.canceledAt = :now,
		       b.updatedAt = :now
		 WHERE b.id = :id
		   AND b.status IN (com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		                    com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED)
		""")
	int markCanceledBySchedule(@Param("id") Long id, @Param("now") LocalDateTime now);

	/**
	 * 결제 완료·일부 취소인 예매의 수정 시각만 바꿔 그 행을 트랜잭션이 끝날 때까지 잠근다. 0행이면 그 상태가 아니다.
	 * 회차 취소 환불을 만들기 전에 부른다. 같은 예매를 바꾸는 중인 직접 취소가 있으면 그 커밋을 기다리게 되어,
	 * 이 뒤에 읽는 남은 좌석에는 직접 취소가 반영돼 있다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Booking b
		   SET b.updatedAt = :now
		 WHERE b.id = :id
		   AND b.status IN (com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		                    com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED)
		""")
	int lockPaid(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** 주어진 회차들에서 주어진 상태인 예매 수. */
	long countByScheduleIdInAndStatusIn(Collection<Long> scheduleIds, Collection<BookingStatus> statuses);

	/**
	 * 회차 취소 환불을 만들 예매 번호. 취소 회차의 결제 완료·일부 취소 예매 중 회차 취소 환불이 아직 없는 것이다.
	 * 실패한 환불도 있는 것으로 본다. (새로 만들지 않고 같은 환불을 다시 보낸다)
	 * 번호 순으로 afterId 뒤부터 가져와, 처리에 실패한 예매가 있어도 뒤의 예매가 막히지 않는다.
	 */
	@Query("""
		SELECT b.id
		  FROM Booking b
		 WHERE b.id > :afterId
		   AND b.status IN (com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		                    com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED)
		   AND b.schedule.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED
		   AND NOT EXISTS (SELECT r.id FROM b.refunds r
		                    WHERE r.reasonType = com.squirrel.tickting.domain.payment.type.RefundReasonType.PERFORMANCE_CANCEL)
		 ORDER BY b.id
		""")
	List<Long> findScheduleRefundTargetIds(@Param("afterId") Long afterId, Limit limit);

	/** 회차 취소 환불이 끝났는데 예매에 아직 반영되지 않은 예매 번호. 번호 순으로 afterId 뒤부터 가져온다. */
	@Query("""
		SELECT b.id
		  FROM Booking b
		 WHERE b.id > :afterId
		   AND b.status IN (com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		                    com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED)
		   AND EXISTS (SELECT r.id FROM b.refunds r
		                WHERE r.reasonType = com.squirrel.tickting.domain.payment.type.RefundReasonType.PERFORMANCE_CANCEL
		                  AND r.status = com.squirrel.tickting.domain.payment.type.RefundStatus.DONE)
		 ORDER BY b.id
		""")
	List<Long> findScheduleCancelApplyTargetIds(@Param("afterId") Long afterId, Limit limit);
}
