package com.squirrel.tickting.domain.booking.infra.repository;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

	/** 일부 취소 처리. 결제 완료·일부 취소인 예매만 바꾸고 바뀐 행 수를 돌려준다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Booking b
		   SET b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED,
		       b.updatedAt = :now
		 WHERE b.id = :id
		   AND b.status IN (com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		                    com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED)
		""")
	int markPartiallyCanceled(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** 전체 취소 처리. 결제 완료·일부 취소인 예매만 바꾸고 전체 취소 일시를 남긴다. */
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
	int markCanceled(@Param("id") Long id, @Param("now") LocalDateTime now);
}
