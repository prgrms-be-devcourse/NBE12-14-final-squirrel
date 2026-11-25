package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 판매 좌석 자동 생성. 테스트에서는 스케줄러를 꺼 두고 스케줄러가 부르는 서비스를 직접 부른다.
 * 자동 생성은 공유 테스트 DB의 모든 대상을 처리하므로 여기서 넣은 회차만 확인한다.
 * 시드에 없는 사업체 번호로 공연을 넣고, 끝나면 그 사업체의 공연과 등급·구역 설정·회차·좌석만 지운다.
 */
class SeatAutoGenerateTest extends IntegrationTestSupport {

	private static final long MY_BUSINESS = 873L;
	private static final String EMPTY_VENUE = "좌석 자동 생성 테스트용 빈 공연장";

	@Autowired
	private SellerPerformanceService sellerPerformanceService;

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id = " + MY_BUSINESS;
		jdbc.update("DELETE FROM schedule_seat WHERE schedule_id IN (SELECT id FROM performance_schedule WHERE performance_id IN ("
			+ mine + "))");
		jdbc.update("DELETE FROM zone_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id = ?", MY_BUSINESS);
		jdbc.update("DELETE FROM venue_zone WHERE venue_id IN (SELECT id FROM venue WHERE name = ?)", EMPTY_VENUE);
		jdbc.update("DELETE FROM venue WHERE name = ?", EMPTY_VENUE);
	}

	private Long sampleVenueId() {
		return jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
	}

	private int sampleVenueSeats() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM venue_seat WHERE venue_id = ?", Integer.class, sampleVenueId());
	}

	/** 판매 정보를 모두 채운 공연. 공연장의 모든 구역을 R 석 판매 구역으로 둔다. 상태는 SQL 로 바로 정한다. */
	private Long insertPerformance(String status, Long venueId, LocalDateTime saleOpenAt, LocalDateTime saleCloseAt) {
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, sale_open_at, sale_close_at, max_per_person,
			                         receive_type, booking_fee, created_at, updated_at)
			VALUES (?, ?, 1, (SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1),
			        '좌석 자동 생성 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, ?, ?, 4,
			        'ONSITE', 1000, NOW(), NOW())
			RETURNING id
			""", Long.class, MY_BUSINESS, venueId, status, Timestamp.valueOf(saleOpenAt), Timestamp.valueOf(saleCloseAt));
		Long gradeId = jdbc.queryForObject(
			"INSERT INTO seat_grade (performance_id, name, price) VALUES (?, 'R', 100000) RETURNING id",
			Long.class, performanceId);
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, assignment_status, grade_id)
			SELECT ?, z.id, 'ASSIGNED', ? FROM venue_zone z WHERE z.venue_id = ?
			""", performanceId, gradeId, venueId);
		return performanceId;
	}

	/** 샘플 공연장의 공연. 판매 오픈만 정하고 판매 종료는 10일 뒤다. */
	private Long insertPerformance(String status, LocalDateTime saleOpenAt) {
		return insertPerformance(status, sampleVenueId(), saleOpenAt, LocalDateTime.now().plusDays(10));
	}

	private Long insertSchedule(Long performanceId, LocalDateTime startAt, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(startAt), status);
	}

	private Long insertSchedule(Long performanceId) {
		return insertSchedule(performanceId, LocalDateTime.now().plusDays(20), "ON_SALE");
	}

	private int seatCount(Long scheduleId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM schedule_seat WHERE schedule_id = ?", Integer.class, scheduleId);
	}

	private Timestamp generatedAt(Long scheduleId) {
		return jdbc.queryForObject("SELECT seats_generated_at FROM performance_schedule WHERE id = ?", Timestamp.class,
			scheduleId);
	}

	private void assertGenerated(Long... scheduleIds) {
		int venueSeats = sampleVenueSeats();
		for (Long scheduleId : scheduleIds) {
			assertThat(seatCount(scheduleId)).as("회차 %d 의 좌석 수", scheduleId).isEqualTo(venueSeats);
			assertThat(generatedAt(scheduleId)).as("회차 %d 의 생성 시각", scheduleId).isNotNull();
		}
	}

	private void assertNotGenerated(Long... scheduleIds) {
		for (Long scheduleId : scheduleIds) {
			assertThat(seatCount(scheduleId)).as("회차 %d 의 좌석 수", scheduleId).isZero();
			assertThat(generatedAt(scheduleId)).as("회차 %d 의 생성 시각", scheduleId).isNull();
		}
	}

	@Test
	@DisplayName("판매 오픈 24시간 전이 지난 공개 공연의 시작 전 회차만 만들고, 일시 중지·연기 회차와 판매가 끝난 공연의 회차도 만든다")
	void generatesOnlyDueSchedules() {
		LocalDateTime now = LocalDateTime.now();
		Long due = insertPerformance("PUBLISHED", now.plusHours(1));
		Long onSale = insertSchedule(due);
		Long paused = insertSchedule(due, now.plusDays(21), "PAUSED");
		Long postponed = insertSchedule(due, now.plusDays(22), "POSTPONED");
		Long saleEnded = insertSchedule(
			insertPerformance("PUBLISHED", sampleVenueId(), now.minusDays(10), now.minusDays(1)),
			now.plusDays(5), "ON_SALE");

		Long draft = insertSchedule(insertPerformance("DRAFT", now.plusHours(1)));
		Long tooEarly = insertSchedule(insertPerformance("PUBLISHED", now.plusHours(25)));
		Long canceledSchedule = insertSchedule(due, now.plusDays(23), "CANCELED");
		Long canceledPerformance = insertSchedule(insertPerformance("CANCELED", now.plusHours(1)));
		Long started = insertSchedule(due, now.minusMinutes(1), "ON_SALE");
		Long alreadyGenerated = insertSchedule(due, now.plusDays(24), "ON_SALE");
		jdbc.update("UPDATE performance_schedule SET seats_generated_at = ? WHERE id = ?",
			Timestamp.valueOf(now.minusDays(1).withNano(0)), alreadyGenerated);
		Timestamp generatedBefore = generatedAt(alreadyGenerated);

		sellerPerformanceService.generateDueSeats();

		assertGenerated(onSale, paused, postponed, saleEnded);
		assertNotGenerated(draft, tooEarly, canceledSchedule, canceledPerformance, started);
		assertThat(seatCount(alreadyGenerated)).isZero();
		assertThat(generatedAt(alreadyGenerated)).isEqualTo(generatedBefore);
	}

	@Test
	@DisplayName("먼저 처리한 회차가 실패하면 그 회차만 되돌아가고, 다음 회차는 계속 만든다")
	void failureDoesNotStopOthers() {
		LocalDateTime now = LocalDateTime.now();
		// 판매 구역은 있는데 공연장 좌석이 없어서, 생성 시각을 채운 뒤 복사에서 실패한다(PERFORMANCE503).
		Long emptyVenueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES (?, '테스트 주소', '서울', 0, 'ACTIVE', NOW(), NOW()) RETURNING id
			""", Long.class, EMPTY_VENUE);
		jdbc.update("INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, '빈 구역', 1)", emptyVenueId);
		// 판매 오픈이 더 빨라 먼저 처리된다.
		Long failing = insertSchedule(
			insertPerformance("PUBLISHED", emptyVenueId, now.minusHours(2), now.plusDays(10)));
		Long normal = insertSchedule(insertPerformance("PUBLISHED", now.plusHours(1)));

		sellerPerformanceService.generateDueSeats();

		assertNotGenerated(failing);
		assertGenerated(normal);
	}

	@Test
	@DisplayName("여러 서버가 동시에 자동 생성을 돌려도 한 회차의 좌석은 공연장 좌석 수만큼만 만들어진다")
	void concurrentRuns() throws Exception {
		Long scheduleId = insertSchedule(insertPerformance("PUBLISHED", LocalDateTime.now().plusHours(1)));

		runConcurrently(5, i -> () -> {
			sellerPerformanceService.generateDueSeats();
			return null;
		});

		assertGenerated(scheduleId);
	}

	@Test
	@DisplayName("자동 생성은 만들기 직전에 다시 확인해서 아직 때가 아닌 회차는 만들지 않고, 같은 회차를 판매자의 버튼으로는 만들 수 있다")
	void rechecksJustBeforeGenerating() {
		// 대상을 읽은 뒤에 판매 오픈이 뒤로 바뀐 상황과 같다.
		Long performanceId = insertPerformance("PUBLISHED", LocalDateTime.now().plusHours(25));
		Long scheduleId = insertSchedule(performanceId);

		assertThat(sellerPerformanceService.generateSeatsBySystem(performanceId, scheduleId)).isEmpty();
		assertNotGenerated(scheduleId);

		// 작성 중 공연은 판매 오픈이 비어 있어도 오류 없이 건너뛴다.
		Long draftId = insertPerformance("DRAFT", LocalDateTime.now().plusHours(1));
		jdbc.update("UPDATE performance SET sale_open_at = NULL WHERE id = ?", draftId);
		Long draftSchedule = insertSchedule(draftId);

		assertThat(sellerPerformanceService.generateSeatsBySystem(draftId, draftSchedule)).isEmpty();
		assertNotGenerated(draftSchedule);

		sellerPerformanceService.generateSeats(MY_BUSINESS, scheduleId);
		assertGenerated(scheduleId);
	}
}
