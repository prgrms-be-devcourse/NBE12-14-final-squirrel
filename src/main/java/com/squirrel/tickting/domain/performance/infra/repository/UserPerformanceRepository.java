package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.type.Genre;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 예매자(User) 화면용 공연 조회. 공연 도메인을 판매자와 함께 쓰므로, 같은 파일을 동시에 고치지 않게 역할 이름을 붙여 나눈다.
 * 저장·삭제는 하지 않으므로 JpaRepository 가 아니라 Repository 를 상속해 조회 메서드만 연다.
 */
public interface UserPerformanceRepository extends Repository<Performance, Long> {

	/**
	 * 공개(PUBLISHED)된 공연 목록·검색. 공연 테이블만 읽고, 회차 일시·남은 좌석은 서비스가 이 페이지의 공연만 따로 읽어 계산한다.
	 * 정렬이 공연 테이블의 값(등록 시각)이라 회차를 묶지 않아도 DB 에서 정렬과 페이지 자르기가 끝나기 때문이다.
	 *
	 *   - genres: 장르를 고르지 않으면 호출하는 쪽이 모든 장르를 넘긴다. (PostgreSQL 에서 null 파라미터가 타입 오류를 내는 것을 피하려고)
	 *   - allVenues, venueIds: 지역은 공연장에 있어서, 지역 검색이면 파사드가 그 지역의 공연장 번호 목록을 받아 넘긴다.
	 *     지역을 고르지 않았으면 allVenues 가 true 이고 venueIds 는 보지 않는다.
	 *   - pattern: LikeUtil.containsPattern 으로 만든 값. 제목이나 출연진에 들어 있으면 일치하고, 검색어가 없으면 "%" 라 전부 일치한다.
	 *     출연진은 비어 있을(null) 수 있어서 OR 로 묶는다. LikeUtil 이 % 와 _ 를 '!' 로 이스케이프하므로 ESCAPE '!' 가 꼭 필요하다.
	 *   - 정렬은 pageable 의 Sort 로 붙인다. 정렬 조건은 UserPerformanceSortType 에만 있다.
	 */
	@Query(value = """
		SELECT p
		FROM Performance p
		WHERE p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		  AND p.genre IN :genres
		  AND (:allVenues = true OR p.venueId IN :venueIds)
		  AND (LOWER(p.title) LIKE :pattern ESCAPE '!' OR LOWER(p.castInfo) LIKE :pattern ESCAPE '!')
		""",
		countQuery = """
			SELECT COUNT(p)
			FROM Performance p
			WHERE p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
			  AND p.genre IN :genres
			  AND (:allVenues = true OR p.venueId IN :venueIds)
			  AND (LOWER(p.title) LIKE :pattern ESCAPE '!' OR LOWER(p.castInfo) LIKE :pattern ESCAPE '!')
			""")
	Page<Performance> findPublished(
		@Param("genres") Collection<Genre> genres,
		@Param("allVenues") boolean allVenues,
		@Param("venueIds") Collection<Long> venueIds,
		@Param("pattern") String pattern,
		Pageable pageable
	);

	/**
	 * 여러 공연의 취소되지 않은 회차. 목록 한 페이지의 공연을 한 번에 읽어서, 공연마다 따로 조회하지 않는다.
	 * 취소된 회차는 공연 일시에도, 남은 좌석 판단에도 쓰지 않는다.
	 */
	@Query("""
		SELECT s
		FROM PerformanceSchedule s
		WHERE s.performance.id IN :performanceIds
		  AND s.status <> com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED
		""")
	List<PerformanceSchedule> findActiveSchedules(@Param("performanceIds") Collection<Long> performanceIds);

	/** 공개(PUBLISHED)된 공연 하나. 작성 중·취소된 공연은 없는 공연과 같게 비어서 돌아온다. */
	@Query("""
		SELECT p
		FROM Performance p
		WHERE p.id = :performanceId
		  AND p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		""")
	Optional<Performance> findPublishedById(@Param("performanceId") Long performanceId);

	/** 판매 가능(AVAILABLE) 좌석이 하나라도 있는 회차 번호. 회차마다 따로 조회하지 않으려고 번호 목록으로 한 번에 찾는다. */
	@Query("""
		SELECT DISTINCT seat.scheduleId
		FROM ScheduleSeat seat
		WHERE seat.scheduleId IN :scheduleIds
		  AND seat.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE
		""")
	List<Long> findScheduleIdsWithAvailableSeat(@Param("scheduleIds") Collection<Long> scheduleIds);

	/**
	 * 구역에 지정(ASSIGNED)된 등급. 어느 구역에도 붙지 않은 등급은 실제로 팔리지 않는 가격이라 보여 주지 않는다.
	 * 여러 구역에 붙은 등급도 한 번만 나오고, 비싼 등급부터(가격이 같으면 번호순) 보여 준다.
	 */
	@Query("""
		SELECT DISTINCT g
		FROM ZoneGrade zg
		JOIN zg.grade g
		WHERE zg.performance.id = :performanceId
		  AND zg.assignmentStatus = com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus.ASSIGNED
		ORDER BY g.price DESC, g.id ASC
		""")
	List<SeatGrade> findAssignedGrades(@Param("performanceId") Long performanceId);
}
