package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 판매 좌석 생성(SLR-14).
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호로 공연을 넣고,
 * 끝나면 그 사업체의 공연과 등급·구역 설정·회차·좌석만 지운다. 회차 추가 API(SLR-12)가 없어 회차도 SQL 로 넣는다.
 */
class SellerSeatGenerateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/schedules/{scheduleId}/seats/generate";
	private static final long MY_BUSINESS = 871L;
	private static final long OTHER_BUSINESS = 872L;
	private static final String EMPTY_VENUE = "좌석 생성 테스트용 빈 공연장";

	@Autowired
	private RedissonClient redissonClient;

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM schedule_seat WHERE schedule_id IN (SELECT id FROM performance_schedule WHERE performance_id IN ("
			+ mine + "))");
		jdbc.update("DELETE FROM zone_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
		jdbc.update("DELETE FROM venue_zone WHERE venue_id IN (SELECT id FROM venue WHERE name = ?)", EMPTY_VENUE);
		jdbc.update("DELETE FROM venue WHERE name = ?", EMPTY_VENUE);
	}

	private Cookie token(AccountType type, String role, Long businessId) {
		return sessionCookie(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 생성은 대표 전용이 아니라서 담당자 토큰으로 보낸다. */
	private ResultActions generate(Long scheduleId) throws Exception {
		return mockMvc.perform(post(URL, scheduleId)
			.cookie(token(AccountType.SELLER, "STAFF", MY_BUSINESS)));
	}

	private Long sampleVenueId() {
		return jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
	}

	private Long defaultPolicyId() {
		return jdbc.queryForObject("SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
	}

	/** 공연을 넣고, 공연 등록처럼 공연장의 모든 구역 설정을 UNSET 으로 만든다. 판매 필드는 비워 둔다. */
	private Long insertPerformance(long businessId, String status, Long venueId) {
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '좌석 생성 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?,
			        NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, defaultPolicyId(), status);
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, assignment_status)
			SELECT ?, z.id, 'UNSET' FROM venue_zone z WHERE z.venue_id = ?
			""", performanceId, venueId);
		return performanceId;
	}

	private void setSale(Long performanceId) {
		jdbc.update("""
			UPDATE performance
			SET sale_open_at = ?, sale_close_at = ?, max_per_person = 4, receive_type = 'ONSITE', booking_fee = 1000
			WHERE id = ?
			""", Timestamp.valueOf(LocalDateTime.now().plusDays(1)), Timestamp.valueOf(LocalDateTime.now().plusDays(10)),
			performanceId);
	}

	private Long insertGrade(Long performanceId, String name) {
		return jdbc.queryForObject(
			"INSERT INTO seat_grade (performance_id, name, price) VALUES (?, ?, 100000) RETURNING id",
			Long.class, performanceId, name);
	}

	private void setZone(Long performanceId, Long zoneId, String status, Long gradeId) {
		jdbc.update("UPDATE zone_grade SET assignment_status = ?, grade_id = ? WHERE performance_id = ? AND zone_id = ?",
			status, gradeId, performanceId, zoneId);
	}

	private List<Long> zoneIds(Long performanceId) {
		return jdbc.queryForList("""
			SELECT z.id FROM venue_zone z
			WHERE z.venue_id = (SELECT venue_id FROM performance WHERE id = ?)
			ORDER BY z.sort_order, z.id
			""", Long.class, performanceId);
	}

	private Long insertSchedule(Long performanceId, LocalDateTime startAt, String status, boolean generated) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at, updated_at)
			VALUES (?, ?, ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(startAt), status,
			generated ? Timestamp.valueOf(LocalDateTime.now().minusDays(1)) : null);
	}

	/**
	 * 만들 수 있는 공개 공연과 그 회차(20일 뒤). 구역은 둘째(sortOrder 순)만 의도적 미판매이고 나머지는 R 석(100,000원)이다.
	 * 공개 API 를 거치지 않고 상태만 바꾼다.
	 */
	private Long insertReadyPerformance(String status) {
		Long performanceId = insertPerformance(MY_BUSINESS, status, sampleVenueId());
		setSale(performanceId);
		Long rGrade = insertGrade(performanceId, "R");
		List<Long> zones = zoneIds(performanceId);
		for (int i = 0; i < zones.size(); i++) {
			if (i == 1) {
				setZone(performanceId, zones.get(i), "INTENTIONALLY_UNSOLD", null);
			} else {
				setZone(performanceId, zones.get(i), "ASSIGNED", rGrade);
			}
		}
		return performanceId;
	}

	private Long readySchedule(Long performanceId) {
		return insertSchedule(performanceId, LocalDateTime.now().plusDays(20), "ON_SALE", false);
	}

	private int seatCount(Long scheduleId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM schedule_seat WHERE schedule_id = ?", Integer.class, scheduleId);
	}

	private boolean generatedAtIsSet(Long scheduleId) {
		return jdbc.queryForObject("SELECT seats_generated_at IS NOT NULL FROM performance_schedule WHERE id = ?",
			Boolean.class, scheduleId);
	}

	/** 막히면 코드와 상태로 응답하고 좌석은 하나도 없고 생성 시각도 비어 있어야 한다. */
	private void assertRejected(Long scheduleId, int httpStatus, String code) throws Exception {
		boolean generatedBefore = generatedAtIsSet(scheduleId);
		int seatsBefore = seatCount(scheduleId);
		generate(scheduleId)
			.andExpect(status().is(httpStatus))
			.andExpect(jsonPath("$.code").value(code));
		assertThat(seatCount(scheduleId)).isEqualTo(seatsBefore);
		assertThat(generatedAtIsSet(scheduleId)).isEqualTo(generatedBefore);
	}

	@Test
	@DisplayName("공개 공연의 회차를 만들면 판매 구역은 AVAILABLE(등급 가격 복사), 의도적 미판매 구역은 BLOCKED(등급·가격 없음)로 만들고 담당자도 쓸 수 있다")
	void generate() throws Exception {
		Long performanceId = insertReadyPerformance("PUBLISHED");
		Long scheduleId = readySchedule(performanceId);
		int venueSeats = jdbc.queryForObject("SELECT COUNT(*) FROM venue_seat WHERE venue_id = ?", Integer.class,
			sampleVenueId());
		int unsoldSeats = jdbc.queryForObject("SELECT COUNT(*) FROM venue_seat WHERE zone_id = ?", Integer.class,
			zoneIds(performanceId).get(1));

		generate(scheduleId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.scheduleId").value(scheduleId))
			.andExpect(jsonPath("$.data.seatsGeneratedAt").isNotEmpty())
			.andExpect(jsonPath("$.data.totalSeats").value(venueSeats))
			.andExpect(jsonPath("$.data.blockedSeats").value(unsoldSeats))
			.andExpect(jsonPath("$.data.availableSeats").value(venueSeats - unsoldSeats));

		assertThat(seatCount(scheduleId)).isEqualTo(venueSeats);
		assertThat(generatedAtIsSet(scheduleId)).isTrue();
		Map<String, Object> available = jdbc.queryForMap("""
			SELECT COUNT(*) AS cnt, MIN(price) AS min_price, MAX(price) AS max_price, COUNT(grade_id) AS graded
			FROM schedule_seat WHERE schedule_id = ? AND status = 'AVAILABLE'
			""", scheduleId);
		assertThat(available.get("min_price")).isEqualTo(100000);
		assertThat(available.get("max_price")).isEqualTo(100000);
		assertThat(available.get("graded")).isEqualTo(available.get("cnt"));
		Map<String, Object> blocked = jdbc.queryForMap("""
			SELECT COUNT(grade_id) AS graded, COUNT(price) AS priced
			FROM schedule_seat WHERE schedule_id = ? AND status = 'BLOCKED'
			""", scheduleId);
		assertThat(blocked.get("graded")).isEqualTo(0L);
		assertThat(blocked.get("priced")).isEqualTo(0L);
	}

	@Test
	@DisplayName("작성 중 공연은 판매 정보를 모두 채웠으면 만들어지고, 비어 있으면 400 PERFORMANCE401 이며 아무것도 만들지 않는다")
	void draftPerformance() throws Exception {
		Long ready = insertReadyPerformance("DRAFT");
		generate(readySchedule(ready)).andExpect(status().isOk());

		Long missing = insertReadyPerformance("DRAFT");
		jdbc.update("UPDATE performance SET booking_fee = NULL WHERE id = ?", missing);
		assertRejected(readySchedule(missing), 400, "PERFORMANCE401");
	}

	@Test
	@DisplayName("취소된 공연·취소된 회차·이미 생성됨·이미 시작함·UNSET 구역·판매 구역 없음은 코드별로 막히고 아무것도 만들지 않는다")
	void rejectsUnmetConditions() throws Exception {
		Long canceledPerformance = insertReadyPerformance("CANCELED");
		assertRejected(readySchedule(canceledPerformance), 409, "PERFORMANCE002");

		Long canceledSchedule = readySchedule(insertReadyPerformance("PUBLISHED"));
		jdbc.update("UPDATE performance_schedule SET status = 'CANCELED' WHERE id = ?", canceledSchedule);
		assertRejected(canceledSchedule, 409, "PERFORMANCE501");

		Long generated = insertSchedule(insertReadyPerformance("PUBLISHED"), LocalDateTime.now().plusDays(20), "ON_SALE", true);
		assertRejected(generated, 409, "PERFORMANCE500");

		Long started = insertSchedule(insertReadyPerformance("PUBLISHED"), LocalDateTime.now().minusMinutes(1), "ON_SALE", false);
		assertRejected(started, 400, "PERFORMANCE502");

		Long unset = insertReadyPerformance("PUBLISHED");
		setZone(unset, zoneIds(unset).get(2), "UNSET", null);
		assertRejected(readySchedule(unset), 400, "PERFORMANCE403");

		Long noAssigned = insertReadyPerformance("PUBLISHED");
		for (Long zoneId : zoneIds(noAssigned)) {
			setZone(noAssigned, zoneId, "INTENTIONALLY_UNSOLD", null);
		}
		assertRejected(readySchedule(noAssigned), 400, "PERFORMANCE404");
	}

	@Test
	@DisplayName("판매 구역에 공연장 좌석이 하나도 없으면 409 PERFORMANCE503 이고, 트랜잭션이 되돌아가 생성 시각도 비어 있다")
	void noVenueSeatsRollsBack() throws Exception {
		Long venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES (?, '테스트 주소', '서울', 0, 'ACTIVE', NOW(), NOW()) RETURNING id
			""", Long.class, EMPTY_VENUE);
		jdbc.update("INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, '빈 구역', 1)", venueId);
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED", venueId);
		setSale(performanceId);
		setZone(performanceId, zoneIds(performanceId).get(0), "ASSIGNED", insertGrade(performanceId, "R"));

		assertRejected(readySchedule(performanceId), 409, "PERFORMANCE503");
	}

	@Test
	@DisplayName("다른 사업체의 회차는 상태와 상관없이 없는 회차와 같은 404 PERFORMANCE008 이고, 예매자는 403, 토큰이 없으면 401이다")
	void permissions() throws Exception {
		Long others = insertReadyPerformance("PUBLISHED");
		jdbc.update("UPDATE performance SET business_id = ? WHERE id = ?", OTHER_BUSINESS, others);
		Long othersSchedule = readySchedule(others);
		Long othersCanceled = readySchedule(others);
		jdbc.update("UPDATE performance_schedule SET status = 'CANCELED' WHERE id = ?", othersCanceled);

		generate(othersSchedule).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		// 남의 회차는 취소된 상태여도 취소 409 보다 404 가 먼저 나와야 남의 회차 상태가 드러나지 않는다.
		generate(othersCanceled).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		generate(Long.MAX_VALUE).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		assertThat(seatCount(othersSchedule)).isZero();

		Long mine = readySchedule(insertReadyPerformance("PUBLISHED"));
		mockMvc.perform(post(URL, mine).cookie(token(AccountType.USER, null, null)))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(URL, mine)).andExpect(status().isUnauthorized());
		assertThat(seatCount(mine)).isZero();
	}

	// ---- 동시성 ----

	@Test
	@DisplayName("같은 회차를 동시에 5번 만들면 한 번만 200 이고 나머지는 409 이며, 좌석은 공연장 좌석 수와 정확히 같다")
	void concurrentGenerate() throws Exception {
		Long performanceId = insertReadyPerformance("PUBLISHED");
		Long scheduleId = readySchedule(performanceId);
		int venueSeats = jdbc.queryForObject("SELECT COUNT(*) FROM venue_seat WHERE venue_id = ?", Integer.class,
			sampleVenueId());

		List<MvcResult> results = runConcurrently(5, i -> () -> generate(scheduleId).andReturn());

		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 200).hasSize(1);
		// 나머지는 이미 생성됨(PERFORMANCE500)이거나 락을 1초 안에 못 잡은 것(COMMON004)이고 둘 다 409 다.
		assertThat(results).filteredOn(result -> result.getResponse().getStatus() != 200)
			.allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));
		assertThat(seatCount(scheduleId)).isEqualTo(venueSeats);
	}

	@Test
	@DisplayName("다른 작업이 공연 락을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고 좌석이 만들어지지 않는다")
	void performanceLockHeldByOthers() throws Exception {
		Long performanceId = insertReadyPerformance("PUBLISHED");
		Long scheduleId = readySchedule(performanceId);
		RLock lock = redissonClient.getLock("tickting:lock:performance:" + performanceId);
		// 락은 잡은 스레드의 것이라, 요청을 보내는 테스트 스레드가 아닌 다른 스레드에서 잡아야 기다리게 된다.
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			generate(scheduleId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMON004"));
		} finally {
			lock.forceUnlock();
		}
		assertThat(seatCount(scheduleId)).isZero();
		assertThat(generatedAtIsSet(scheduleId)).isFalse();
	}

	@Test
	@DisplayName("좌석 생성과 등급 가격 수정이 동시에 와도 순서대로 처리되어, 만들어진 좌석 가격은 항상 최종 등급 가격과 같다")
	void concurrentWithGradePriceUpdate() throws Exception {
		Long performanceId = insertReadyPerformance("PUBLISHED");
		Long scheduleId = readySchedule(performanceId);
		Long gradeId = jdbc.queryForObject("SELECT id FROM seat_grade WHERE performance_id = ?", Long.class, performanceId);

		List<MvcResult> results = runConcurrently(2, i -> i == 0
			? () -> generate(scheduleId).andReturn()
			: () -> mockMvc.perform(put("/api/v1/seller/performances/{performanceId}/grades/{gradeId}", performanceId, gradeId)
				.cookie(token(AccountType.SELLER, "STAFF", MY_BUSINESS))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"R\",\"price\":200000}")).andReturn());

		assertThat(results.get(0).getResponse().getStatus()).isEqualTo(200);
		Integer gradePrice = jdbc.queryForObject("SELECT price FROM seat_grade WHERE id = ?", Integer.class, gradeId);
		// 생성이 먼저면 수정이 잠금(PERFORMANCE301)으로 거절되어 가격이 그대로이고, 수정이 먼저면 좌석이 새 가격을 복사한다.
		assertThat(jdbc.queryForList(
			"SELECT DISTINCT price FROM schedule_seat WHERE schedule_id = ? AND status = 'AVAILABLE'", Integer.class,
			scheduleId)).containsExactly(gradePrice);
	}
}
