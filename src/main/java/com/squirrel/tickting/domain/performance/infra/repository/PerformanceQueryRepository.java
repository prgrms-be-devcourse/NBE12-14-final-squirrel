package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.type.Genre;
import java.time.LocalDateTime;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 예매자 화면용 공연 조회. 판매자 쪽 PerformanceRepository 와 나눈 것은, 같은 파일을 두 사람이 동시에 고치면 충돌이 나기 때문이다.
 * 저장·삭제는 하지 않으므로 JpaRepository 가 아니라 Repository 를 상속해 조회 메서드만 연다.
 */
public interface PerformanceQueryRepository extends Repository<Performance, Long> {

	/**
	 * 공개(PUBLISHED)된 공연 목록. 회차·판매 좌석은 같은 공연 도메인이라 한 쿼리로 모으고, 공연장은 읽지 않는다.
	 *
	 *   - 취소된 회차는 공연 일시에서 뺀다. (WHERE 가 아니라 JOIN 조건에 넣어야 회차가 전부 취소된 공연도 빠지지 않는다)
	 *   - 매진 판단은 아직 시작하지 않은 회차의 AVAILABLE 좌석만 본다. 임시 점유(HELD)만 남았으면 지금은 살 수 없으니 매진이고,
	 *     점유가 풀려 AVAILABLE 로 돌아오면 다음 조회부터 다시 판매 중이 된다. (상태를 저장하지 않고 계산하므로 따로 되돌릴 일이 없다)
	 *   - genres: 장르를 고르지 않으면 호출하는 쪽이 모든 장르를 넘긴다. (PostgreSQL 에서 null 파라미터가 타입 오류를 내는 것을 피하려고)
	 *   - 정렬은 pageable 의 Sort 로 붙인다. 정렬 조건은 PerformanceSortType 에만 있고, 이 쿼리의 별칭 p, s 와 :now 를 쓴다.
	 *
	 * GROUP BY 는 공연 번호만 적는다. PostgreSQL 은 기본키로 묶으면 같은 행의 다른 컬럼을 그대로 읽게 해 준다.
	 */
	@Query(value = """
		SELECT new com.squirrel.tickting.domain.performance.infra.repository.PerformanceSummaryRow(
		         p.id, p.title, p.genre, p.posterUrl, p.ageLimit, p.venueId,
		         MIN(s.startAt), MAX(s.startAt),
		         p.saleOpenAt, p.saleCloseAt,
		         CASE WHEN EXISTS (
		                SELECT 1
		                FROM ScheduleSeat seat, PerformanceSchedule upcoming
		                WHERE seat.scheduleId = upcoming.id
		                  AND upcoming.performance = p
		                  AND upcoming.status <> com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED
		                  AND upcoming.startAt > :now
		                  AND seat.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE
		              ) THEN true ELSE false END)
		FROM Performance p
		LEFT JOIN PerformanceSchedule s
		       ON s.performance = p
		      AND s.status <> com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED
		WHERE p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		  AND p.genre IN :genres
		GROUP BY p.id
		""",
		countQuery = """
			SELECT COUNT(p)
			FROM Performance p
			WHERE p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
			  AND p.genre IN :genres
			""")
	Page<PerformanceSummaryRow> findPublished(
		@Param("genres") Collection<Genre> genres,
		@Param("now") LocalDateTime now,
		Pageable pageable
	);
}
