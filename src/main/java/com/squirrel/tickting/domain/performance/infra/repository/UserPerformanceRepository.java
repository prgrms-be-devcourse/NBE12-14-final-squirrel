package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.CommonGuide;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.SeatStatus;
import java.time.LocalDateTime;
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
	 * 공개(PUBLISHED)된 공연 목록·검색. 응답에 쓰는 값만 한 번에 읽는다. 결과는 맨 아래 PublishedRow record 로 받는다.
	 * 공연 엔티티를 읽으면 쓰지 않는 컬럼(긴 text 3개 등)과 회차·공연장 지연 로딩 쿼리가 따라와서 앱 CPU 가 컸다. (부하 테스트 JFR)
	 * 첫·마지막 회차 일시(취소된 회차는 뺀다), "일시 중지 상태인지", "판매 가능 좌석이 있는지"는 공연마다 서브쿼리로 계산한다.
	 * 뒤의 둘은 아직 시작 전인 회차만 본다. 좌석은 판매 중(ON_SALE) 회차 것만 센다. (중지한 회차의 좌석은 지금 살 수 없다)
	 * 정렬이 공연 테이블의 값(등록 시각)이라 DB 에서 정렬과 페이지 자르기가 끝난다.
	 *
	 *   - allGenres, genre: 장르를 고르지 않으면 allGenres 가 true 이고 genre 는 보지 않는다. (아무 값이나 넘긴다. null 파라미터는 PostgreSQL 에서 타입 오류를 낸다)
	 *     IN 에 장르 목록을 넘기면 목록 길이마다 쿼리 번역이 새로 일어나서(계획 캐시가 안 걸린다) 앱 CPU 를 썼다. 그래서 값 하나와 플래그로 받는다.
	 *   - allRegions, region: 지역은 공연장에 있어서 공연장을 LEFT JOIN 해서 지역이 같은 공연만 찾는다. (공연장 행이 없는 공연도 지역을 안 고르면 나온다)
	 *     지역을 고르지 않았으면 allRegions 가 true 이고 region 은 보지 않는다. (빈 문자열을 넘긴다. null 파라미터는 PostgreSQL 에서 타입 오류를 낸다)
	 *   - now: 이 시각 이후에 시작하는 회차만 "판매 가능 좌석이 있는지"를 본다. 서비스가 판매 상태 계산에 쓰는 시각과 같은 값을 넘긴다.
	 *   - pattern: LikeUtil.containsPattern 으로 만든 값. 제목이나 출연진에 들어 있으면 일치하고, 검색어가 없으면 "%" 라 전부 일치한다.
	 *     출연진은 비어 있을(null) 수 있어서 OR 로 묶는다. LikeUtil 이 % 와 _ 를 '!' 로 이스케이프하므로 ESCAPE '!' 가 꼭 필요하다.
	 *   - 정렬은 pageable 의 Sort 로 붙인다. 정렬 조건은 UserPerformanceSortType 에만 있다.
	 */
	@Query(value = """
		SELECT new com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository$PublishedRow(
		       p.id, p.title, p.genre, p.posterUrl, p.ageLimit, v.name, v.region, p.saleOpenAt, p.saleCloseAt,
		       (SELECT MIN(s.startAt) FROM PerformanceSchedule s
		         WHERE s.performance.id = p.id
		           AND s.status <> com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED),
		       (SELECT MAX(s.startAt) FROM PerformanceSchedule s
		         WHERE s.performance.id = p.id
		           AND s.status <> com.squirrel.tickting.domain.performance.type.ScheduleStatus.CANCELED),
		       CASE WHEN EXISTS (
		         SELECT 1 FROM PerformanceSchedule s
		          WHERE s.performance.id = p.id
		            AND s.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.PAUSED
		            AND s.startAt > :now)
		         AND NOT EXISTS (
		         SELECT 1 FROM PerformanceSchedule s
		          WHERE s.performance.id = p.id
		            AND s.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.ON_SALE
		            AND s.startAt > :now)
		       THEN true ELSE false END,
		       CASE WHEN EXISTS (
		         SELECT 1 FROM PerformanceSchedule s
		          WHERE s.performance.id = p.id
		            AND s.status = com.squirrel.tickting.domain.performance.type.ScheduleStatus.ON_SALE
		            AND s.startAt > :now
		            AND EXISTS (
		              SELECT 1 FROM ScheduleSeat seat
		               WHERE seat.scheduleId = s.id
		                 AND seat.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE))
		       THEN true ELSE false END)
		FROM Performance p
		LEFT JOIN p.venue v
		WHERE p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		  AND (:allGenres = true OR p.genre = :genre)
		  AND (:allRegions = true OR v.region = :region)
		  AND (LOWER(p.title) LIKE :pattern ESCAPE '!' OR LOWER(p.castInfo) LIKE :pattern ESCAPE '!')
		""",
		countQuery = """
			SELECT COUNT(p)
			FROM Performance p
			LEFT JOIN p.venue v
			WHERE p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
			  AND (:allGenres = true OR p.genre = :genre)
			  AND (:allRegions = true OR v.region = :region)
			  AND (LOWER(p.title) LIKE :pattern ESCAPE '!' OR LOWER(p.castInfo) LIKE :pattern ESCAPE '!')
			""")
	Page<PublishedRow> findPublished(
		@Param("allGenres") boolean allGenres,
		@Param("genre") Genre genre,
		@Param("allRegions") boolean allRegions,
		@Param("region") String region,
		@Param("pattern") String pattern,
		@Param("now") LocalDateTime now,
		Pageable pageable
	);

	/**
	 * 공개(PUBLISHED)된 공연 하나. 작성 중·취소된 공연은 없는 공연과 같게 비어서 돌아온다.
	 * 상세 응답이 공연장·판매자를 쓰므로 함께 읽는다. 따로 두면 지연 로딩으로 쿼리가 두 번 더 나간다.
	 */
	@Query("""
		SELECT p
		FROM Performance p
		LEFT JOIN FETCH p.venue
		LEFT JOIN FETCH p.business
		WHERE p.id = :performanceId
		  AND p.status = com.squirrel.tickting.domain.performance.type.PerformanceStatus.PUBLISHED
		""")
	Optional<Performance> findPublishedById(@Param("performanceId") Long performanceId);

	/**
	 * 모든 공연에 공통인 안내(유형마다 한 행). findAll() 은 매번 쿼리를 새로 번역해서(계획 캐시가 안 걸린다) 상세 요청마다 앱 CPU 를 썼다.
	 * 고정된 @Query 는 번역 결과가 캐시되므로 이 쿼리로 읽는다.
	 */
	@Query("SELECT g FROM CommonGuide g")
	List<CommonGuide> findCommonGuides();

	/**
	 * 공연에서 판매 가능(AVAILABLE) 좌석이 하나라도 있는 회차 번호. 회차마다 따로 조회하지 않으려고 공연 하나로 한 번에 찾는다.
	 * 좌석을 DISTINCT 로 모으면 AVAILABLE 좌석 수만큼(수만 건) 훑어서 상세 요청마다 느려진다.
	 * EXISTS 는 회차마다 좌석 하나만 찾으면 끝나서 좌석 수와 상관없이 빠르다. (부하 테스트에서 DB 시간의 대부분이었다)
	 * 회차 번호 목록을 IN 으로 넘기면 목록 길이마다 쿼리 번역이 새로 일어나서, 공연 번호 하나로 찾는다. 취소된 회차도 나올 수 있어서 쓰는 쪽이 거른다.
	 */
	@Query("""
		SELECT s.id
		FROM PerformanceSchedule s
		WHERE s.performance.id = :performanceId
		  AND EXISTS (
		    SELECT 1 FROM ScheduleSeat seat
		    WHERE seat.scheduleId = s.id
		      AND seat.status = com.squirrel.tickting.domain.performance.type.SeatStatus.AVAILABLE)
		""")
	List<Long> findScheduleIdsWithAvailableSeat(@Param("performanceId") Long performanceId);

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
	 * idx_schedule_seat_available (schedule_id, zone_id) WHERE status = 'AVAILABLE' 를 탄다.
	 * 회차 좌석은 수천 개인데 판매 가능한 것만 담은 작은 인덱스라서 훑는 양이 적고, 팔릴수록 인덱스도 줄어든다. (V10)
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
	 * 공연장의 구역 하나의 번호와 이름. 구역 좌석 응답이 구역의 번호와 이름만 쓰므로, 공연장의 구역 전체나 좌석 수(@Formula 서브쿼리)를
	 * 읽지 않으려고 두 값만 읽는다. 공연장 번호를 함께 걸어서 다른 공연장의 구역 번호로는 찾지 못한다.
	 */
	@Query("""
		SELECT z.id AS id, z.name AS name
		FROM VenueZone z
		WHERE z.id = :zoneId
		  AND z.venue.id = :venueId
		""")
	Optional<ZoneName> findZoneName(@Param("zoneId") Long zoneId, @Param("venueId") Long venueId);

	/**
	 * 회차의 한 구역에 있는 판매 좌석. 상태와 상관없이 모두 읽는다. (화면이 판매 완료·점유 중 좌석도 색을 달리해 보여 준다)
	 * 응답에 나가는 다섯 값(번호, 상태, 열, 번호, 표시 순서)만 읽는다. 좌석을 구역마다 수백 개 읽어서 엔티티를 만들면
	 * 읽는 컬럼과 객체 만드는 비용이 커서 DB 시간과 앱 CPU 의 큰 몫이었다. (부하 테스트)
	 * 결과는 맨 아래 ZoneSeat record 로 받는다. 인터페이스 프로젝션은 행마다 프록시를 만들어서 좌석이 많은 이 조회에서는 record 가 가볍다.
	 * (SELECT new 의 인자 순서가 record 필드 순서와 같아야 한다)
	 * 구역 안의 표시 순서(공연장 좌석의 sortOrder, 같으면 번호순)로 정렬한다.
	 * idx_schedule_seat_zone_status (schedule_id, zone_id, status) 를 탄다.
	 */
	@Query("""
		SELECT new com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository$ZoneSeat(
		       seat.id, seat.status, vs.rowLabel, vs.seatNumber, vs.sortOrder)
		FROM ScheduleSeat seat
		JOIN seat.venueSeat vs
		WHERE seat.scheduleId = :scheduleId
		  AND seat.zoneId = :zoneId
		ORDER BY vs.sortOrder ASC, vs.id ASC
		""")
	List<ZoneSeat> findScheduleSeats(@Param("scheduleId") Long scheduleId, @Param("zoneId") Long zoneId);

	/** 구역별 판매 가능 좌석 수 쿼리의 결과 한 줄 */
	interface ZoneRemaining {

		Long getZoneId();

		Long getRemaining();
	}

	/** 구역 번호·이름 쿼리의 결과 */
	interface ZoneName {

		Long getId();

		String getName();
	}

	// ====================================================================================
	// record 프로젝션 (SELECT new ...). 위의 인터페이스 프로젝션과 받는 방식이 달라서 구분해 아래에 둔다.
	// 별칭(AS)이 아니라 생성자 인자의 순서와 타입으로 맞춘다. 행마다 프록시를 만들지 않는다.
	// ====================================================================================

	/** 구역 좌석 쿼리의 결과 한 줄. 필드 순서는 findScheduleSeats 의 SELECT new 인자 순서와 같다. */
	record ZoneSeat(Long seatId, SeatStatus status, String rowLabel, String seatNumber, Integer sortOrder) {
	}

	/**
	 * 공연 목록 쿼리의 결과 한 줄. 필드 순서는 findPublished 의 SELECT new 인자 순서와 같다.
	 *
	 * @param venueName         공연장 행이 없는 공연은 null
	 * @param region            공연장 행이 없는 공연은 null
	 * @param firstStartAt      취소되지 않은 회차 중 가장 이른 공연 일시. 회차가 없으면 null
	 * @param lastStartAt       취소되지 않은 회차 중 가장 늦은 공연 일시. 회차가 없으면 null
	 * @param paused            아직 시작하지 않은 회차에 일시 중지는 있고 판매 중은 없는지
	 * @param hasAvailableSeat  아직 시작하지 않은 판매 중 회차에 판매 가능(AVAILABLE) 좌석이 하나라도 있는지
	 */
	record PublishedRow(Long id, String title, Genre genre, String posterUrl, Integer ageLimit, String venueName,
		String region, LocalDateTime saleOpenAt, LocalDateTime saleCloseAt, LocalDateTime firstStartAt,
		LocalDateTime lastStartAt, Boolean paused, Boolean hasAvailableSeat) {
	}
}
