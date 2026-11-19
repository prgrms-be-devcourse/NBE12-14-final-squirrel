package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
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
	 *   - allRegions, region: 지역은 공연장에 있어서 공연장을 LEFT JOIN 해서 지역이 같은 공연만 찾는다. (공연장 행이 없는 공연도 지역을 안 고르면 나온다)
	 *     지역을 고르지 않았으면 allRegions 가 true 이고 region 은 보지 않는다. (빈 문자열을 넘긴다. null 파라미터는 PostgreSQL 에서 타입 오류를 낸다)
	 *   - pattern: LikeUtil.containsPattern 으로 만든 값. 제목이나 출연진에 들어 있으면 일치하고, 검색어가 없으면 "%" 라 전부 일치한다.
	 *     출연진은 비어 있을(null) 수 있어서 OR 로 묶는다. LikeUtil 이 % 와 _ 를 '!' 로 이스케이프하므로 ESCAPE '!' 가 꼭 필요하다.
	 *   - 정렬은 pageable 의 Sort 로 붙인다. 정렬 조건은 UserPerformanceSortType 에만 있다.
	 */
	@Query(value = """
		SELECT p
		FROM Performance p
		LEFT JOIN p.venue v
		WHERE p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		  AND p.genre IN :genres
		  AND (:allRegions = true OR v.region = :region)
		  AND (LOWER(p.title) LIKE :pattern ESCAPE '!' OR LOWER(p.castInfo) LIKE :pattern ESCAPE '!')
		""",
		countQuery = """
			SELECT COUNT(p)
			FROM Performance p
			LEFT JOIN p.venue v
			WHERE p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
			  AND p.genre IN :genres
			  AND (:allRegions = true OR v.region = :region)
			  AND (LOWER(p.title) LIKE :pattern ESCAPE '!' OR LOWER(p.castInfo) LIKE :pattern ESCAPE '!')
			""")
	Page<Performance> findPublished(
		@Param("genres") Collection<Genre> genres,
		@Param("allRegions") boolean allRegions,
		@Param("region") String region,
		@Param("pattern") String pattern,
		Pageable pageable
	);

	/** 공개(PUBLISHED)된 공연 하나. 작성 중·취소된 공연은 없는 공연과 같게 비어서 돌아온다. */
	@Query("""
		SELECT p
		FROM Performance p
		WHERE p.id = :performanceId
		  AND p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		""")
	Optional<Performance> findPublishedById(@Param("performanceId") Long performanceId);

	/**
	 * 판매 가능(AVAILABLE) 좌석이 하나라도 있는 회차 번호. 회차마다 따로 조회하지 않으려고 번호 목록으로 한 번에 찾는다.
	 * 좌석을 DISTINCT 로 모으면 AVAILABLE 좌석 수만큼(수만 건) 훑어서 목록·상세 요청마다 느려진다.
	 * EXISTS 는 회차마다 좌석 하나만 찾으면 끝나서 좌석 수와 상관없이 빠르다. (부하 테스트에서 DB 시간의 대부분이었다)
	 */
	@Query("""
		SELECT s.id
		FROM PerformanceSchedule s
		WHERE s.id IN :scheduleIds
		  AND EXISTS (
		    SELECT 1 FROM ScheduleSeat seat
		    WHERE seat.scheduleId = s.id
		      AND seat.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE)
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

	/**
	 * 예매자가 볼 수 있는 회차 하나. 공개(PUBLISHED)된 공연의 취소되지 않은 회차만 찾는다. (공연 상세의 회차 목록과 같은 기준)
	 * 일시 중지·연기된 회차는 찾는다. 공연장 번호가 필요해서 공연을 함께 읽는다.
	 */
	@Query("""
		SELECT s
		FROM PerformanceSchedule s
		JOIN FETCH s.performance p
		WHERE s.id = :scheduleId
		  AND p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		  AND s.status <> com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED
		""")
	Optional<PerformanceSchedule> findVisibleSchedule(@Param("scheduleId") Long scheduleId);

	/** 공연의 구역별 등급 설정. 등급 이름·가격·색을 트랜잭션 밖에서 꺼내므로 등급을 함께 읽는다. (미판매 구역은 등급이 없다) */
	@Query("""
		SELECT zg
		FROM ZoneGrade zg
		LEFT JOIN FETCH zg.grade
		WHERE zg.performance.id = :performanceId
		""")
	List<ZoneGrade> findZoneGrades(@Param("performanceId") Long performanceId);

	/**
	 * 회차의 구역별 판매 가능(AVAILABLE) 좌석 수. 판매 가능 좌석이 없는 구역은 결과에 나오지 않는다.
	 * 결과는 아래 ZoneRemaining 으로 받는다. (별칭 zoneId, remaining 이 getter 이름과 같아야 한다)
	 * idx_schedule_seat_zone_status (schedule_id, zone_id, status) 를 탄다.
	 */
	@Query("""
		SELECT seat.zoneId AS zoneId, COUNT(seat) AS remaining
		FROM ScheduleSeat seat
		WHERE seat.scheduleId = :scheduleId
		  AND seat.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE
		GROUP BY seat.zoneId
		""")
	List<ZoneRemaining> countAvailableSeatsByZone(@Param("scheduleId") Long scheduleId);

	/**
	 * 회차의 한 구역에 있는 판매 좌석. 상태와 상관없이 모두 읽는다. (화면이 판매 완료·점유 중 좌석도 색을 달리해 보여 준다)
	 * 열·번호는 공연장 좌석에 있어서 함께 읽고, 구역 안의 표시 순서(공연장 좌석의 sortOrder, 같으면 번호순)로 정렬한다.
	 * idx_schedule_seat_zone_status (schedule_id, zone_id, status) 를 탄다.
	 */
	@Query("""
		SELECT seat
		FROM ScheduleSeat seat
		JOIN FETCH seat.venueSeat vs
		WHERE seat.scheduleId = :scheduleId
		  AND seat.zoneId = :zoneId
		ORDER BY vs.sortOrder ASC, vs.id ASC
		""")
	List<ScheduleSeat> findScheduleSeats(@Param("scheduleId") Long scheduleId, @Param("zoneId") Long zoneId);

	/** 구역별 판매 가능 좌석 수 쿼리의 결과 한 줄 */
	interface ZoneRemaining {

		Long getZoneId();

		Long getRemaining();
	}
}
