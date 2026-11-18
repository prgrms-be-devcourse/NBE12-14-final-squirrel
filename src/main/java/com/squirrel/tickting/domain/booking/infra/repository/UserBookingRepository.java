package com.squirrel.tickting.domain.booking.infra.repository;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import jakarta.persistence.Tuple;
import java.util.Collection;
import java.util.List;
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
	 */
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
	 * 예매별 좌석 수. 취소된 좌석도 센다. (처음 예매한 좌석 수라서 처음 결제 금액 total_amount 와 기준이 같다)
	 * 결과 타입을 따로 만들지 않고 JPA 의 Tuple 로 받는다. (별칭 bookingId, seatCount)
	 */
	@Query("""
		SELECT bs.bookingId AS bookingId, COUNT(bs) AS seatCount
		FROM BookingSeat bs
		WHERE bs.bookingId IN :bookingIds
		GROUP BY bs.bookingId
		""")
	List<Tuple> countSeats(@Param("bookingIds") Collection<Long> bookingIds);
}
