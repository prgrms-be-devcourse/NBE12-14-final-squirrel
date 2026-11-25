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
 * 예매자·판매자·관리자가 함께 쓰는 예매 조회. 범위(누구의 예매인가)마다 메서드를 따로 둔다.
 * "값이 있으면 거르기"(:userId IS NULL OR ...)로 하나로 합치면 DB 가 인덱스를 고르지 못하고,
 * 메서드 이름만 봐서는 무엇으로 거르는지 알 수 없어 리뷰에서 빠뜨리기 쉽다.
 *
 * 목록은 모두 예매 시각 최신순, 같으면 번호가 큰 순으로 고정한다(페이지를 넘겨도 순서가 바뀌지 않게).
 * PostgreSQL 에서 null 파라미터가 타입 오류를 내서 선택 조건은 "전부 넘기기"로 쓴다:
 * 상태는 허용할 값 목록, 날짜는 넓은 기본 구간, 제목은 빈 문자열이면 거르지 않는다.
 * 제목은 소문자로 바꾼 LIKE 패턴을 받고 '!' 로 %·_ 를 이스케이프한다.
 */
public interface BookingQueryRepository extends Repository<Booking, Long> {

	/** 예매자의 예매 목록. idx_booking_user (user_id, created_at DESC) 를 탄다. */
	@Query(value = """
		SELECT b FROM Booking b JOIN b.performance p
		 WHERE b.userId = :userId
		   AND b.status IN :statuses
		   AND b.createdAt >= :from AND b.createdAt < :to
		   AND (:title = '' OR LOWER(p.title) LIKE :title ESCAPE '!')
		 ORDER BY b.createdAt DESC, b.id DESC
		""",
		countQuery = """
			SELECT COUNT(b) FROM Booking b JOIN b.performance p
			 WHERE b.userId = :userId
			   AND b.status IN :statuses
			   AND b.createdAt >= :from AND b.createdAt < :to
			   AND (:title = '' OR LOWER(p.title) LIKE :title ESCAPE '!')
			""")
	Page<Booking> searchOfUser(@Param("userId") Long userId, @Param("statuses") Collection<BookingStatus> statuses,
		@Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("title") String title,
		Pageable pageable);

	/** 판매자의 사업체 전체 예매 목록. idx_booking_business_created (business_id, created_at DESC, id DESC) 를 탄다. */
	@Query(value = """
		SELECT b FROM Booking b JOIN b.performance p
		 WHERE b.businessId = :businessId
		   AND b.status IN :statuses
		   AND b.createdAt >= :from AND b.createdAt < :to
		   AND (:title = '' OR LOWER(p.title) LIKE :title ESCAPE '!')
		 ORDER BY b.createdAt DESC, b.id DESC
		""",
		countQuery = """
			SELECT COUNT(b) FROM Booking b JOIN b.performance p
			 WHERE b.businessId = :businessId
			   AND b.status IN :statuses
			   AND b.createdAt >= :from AND b.createdAt < :to
			   AND (:title = '' OR LOWER(p.title) LIKE :title ESCAPE '!')
			""")
	Page<Booking> searchOfBusiness(@Param("businessId") Long businessId,
		@Param("statuses") Collection<BookingStatus> statuses, @Param("from") LocalDateTime from,
		@Param("to") LocalDateTime to, @Param("title") String title, Pageable pageable);

	/** 관리자의 전체 예매 목록. idx_booking_created (created_at DESC, id DESC) 를 탄다. */
	@Query(value = """
		SELECT b FROM Booking b JOIN b.performance p
		 WHERE b.status IN :statuses
		   AND b.createdAt >= :from AND b.createdAt < :to
		   AND (:title = '' OR LOWER(p.title) LIKE :title ESCAPE '!')
		 ORDER BY b.createdAt DESC, b.id DESC
		""",
		countQuery = """
			SELECT COUNT(b) FROM Booking b JOIN b.performance p
			 WHERE b.status IN :statuses
			   AND b.createdAt >= :from AND b.createdAt < :to
			   AND (:title = '' OR LOWER(p.title) LIKE :title ESCAPE '!')
			""")
	Page<Booking> searchAll(@Param("statuses") Collection<BookingStatus> statuses, @Param("from") LocalDateTime from,
		@Param("to") LocalDateTime to, @Param("title") String title, Pageable pageable);

	/** 예매자의 예매 하나. 남의 예매는 비어서 돌아온다. */
	Optional<Booking> findByIdAndUserId(Long id, Long userId);

	/** 판매자의 예매 하나. 다른 사업체 공연의 예매는 비어서 돌아온다. */
	Optional<Booking> findByIdAndBusinessId(Long id, Long businessId);

	/** 관리자의 예매 하나. */
	Optional<Booking> findById(Long id);
}
