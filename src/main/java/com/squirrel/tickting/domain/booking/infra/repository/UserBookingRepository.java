package com.squirrel.tickting.domain.booking.infra.repository;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 예매자(User) 화면용 예매 조회. 예매 도메인을 예매 생성·취소(C)와 판매자 조회(A)가 함께 쓰므로,
 * 같은 파일을 동시에 고치지 않게 역할 이름을 붙여 나눈다. 저장은 하지 않아서 조회 메서드만 연다.
 */
public interface UserBookingRepository extends Repository<Booking, Long> {

	/**
	 * 한 예매자의 예매 목록. 상태를 고르지 않으면 호출하는 쪽이 모든 상태를 넘긴다. (PostgreSQL 에서 null 파라미터가 타입 오류를 내서)
	 * 정렬은 pageable 의 Sort 로 붙인다. idx_booking_user (user_id, created_at DESC) 를 탄다.
	 *
	 * @deprecated 역할별 예매 조회를 BookingQueryService 로 모았다. 새 코드는 BookingQueryRepository.searchOfUser 를 쓴다. 이미 쓰는 곳이 있어서 지우지 않고 남겨 둔다.
	 */
	@Deprecated(since = "2026-11-25")
	@Query(value = """
		SELECT b
		FROM Booking b
		WHERE b.userId = :userId
		  AND b.status IN :statuses
		""",
		countQuery = """
			SELECT COUNT(b)
			FROM Booking b
			WHERE b.userId = :userId
			  AND b.status IN :statuses
			""")
	Page<Booking> findMine(
		@Param("userId") Long userId,
		@Param("statuses") Collection<BookingStatus> statuses,
		Pageable pageable
	);

	/**
	 * 탈퇴를 막아야 하는 진행 중인 예매가 있는지. 결제 제한 시각이 남은 결제 대기 예매, 또는 공연이 시작하지 않은 결제 완료·일부 취소 예매다.
	 * 공연 당일은 취소 불가 구간이라 시작한 뒤에는 취소·환불로 돈이 오갈 일이 없어서 시작 시각까지만 막는다.
	 * 회차가 날짜를 바꿔 연기되면 시작 시각이 바뀌므로 그 회차의 예매는 다시 진행 중이 된다. 일정 미정(POSTPONED)이면
	 * 원래 시작 시각이 지나도 진행 중이다. 탈퇴하면 받을 수 있는 환불을 잃는다.
	 * 결제 대기는 7분이 지나면 주기 작업이 만료로 바꾸는데, 그 전이라도 제한 시각이 지났으면 이미 결제할 수 없어서 막지 않는다.
	 */
	@Query("""
		SELECT COUNT(b) > 0
		FROM Booking b
		WHERE b.userId = :userId
		  AND ((b.status = com.squirrel.tickting.domain.booking.type.BookingStatus.PENDING AND b.expiresAt > :now)
		    OR (b.status IN (com.squirrel.tickting.domain.booking.type.BookingStatus.PAID,
		                     com.squirrel.tickting.domain.booking.type.BookingStatus.PARTIAL_CANCELED)
		        AND (b.schedule.startAt > :now
		          OR b.schedule.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.POSTPONED)))
		""")
	boolean existsOngoing(@Param("userId") Long userId, @Param("now") LocalDateTime now);

	/** 한 예매자의 예매 하나. 회원 번호를 조건에 넣어서 남의 예매는 없는 것처럼 비어서 돌아온다. */
	Optional<Booking> findByIdAndUserId(Long id, Long userId);
}
