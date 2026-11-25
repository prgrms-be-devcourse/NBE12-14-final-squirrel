package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 회차 추가(SLR-12).
 * 시간 겹침 검사가 공연장의 모든 회차를 보기 때문에, 다른 테스트의 회차가 섞이지 않게 전용 공연장을 만든다.
 * 끝나면 전용 공연장과 시드에 없는 사업체 번호의 공연만 지운다.
 */
class SellerScheduleCreateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances/{performanceId}/schedules";
	private static final long MY_BUSINESS = 981L;
	private static final long OTHER_BUSINESS = 982L;
	private static final String VENUE_NAME = "회차 추가 테스트 공연장";
	private static final String OVERLAP_MESSAGE = "같은 공연장의 다른 회차와 시간이 겹칩니다.";
	private static final DateTimeFormatter REQUEST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	private static final DateTimeFormatter MESSAGE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	@Autowired
	private RedissonClient redissonClient;

	private Long venueId;
	private Long saleZoneId;
	private Long unsoldZoneId;

	/** 구역 둘에 좌석이 각각 3개, 2개인 공연장. */
	@BeforeEach
	void insertVenue() {
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES (?, '테스트 주소', '서울', 5, 'ACTIVE', NOW(), NOW()) RETURNING id
			""", Long.class, VENUE_NAME);
		saleZoneId = insertZone("A구역", 1, 3);
		unsoldZoneId = insertZone("B구역", 2, 2);
	}

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		String schedules = "SELECT id FROM performance_schedule WHERE performance_id IN (" + mine + ")";
		jdbc.update("DELETE FROM schedule_seat WHERE schedule_id IN (" + schedules + ")");
		jdbc.update("DELETE FROM schedule_postponement WHERE schedule_id IN (" + schedules + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM zone_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
		jdbc.update("DELETE FROM venue_seat WHERE venue_id = ?", venueId);
		jdbc.update("DELETE FROM venue_zone WHERE venue_id = ?", venueId);
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
	}

	private Long insertZone(String name, int sortOrder, int seats) {
		Long zoneId = jdbc.queryForObject(
			"INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, ?, ?) RETURNING id",
			Long.class, venueId, name, sortOrder);
		for (int number = 1; number <= seats; number++) {
			jdbc.update("INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order) VALUES (?, ?, '1', ?, ?)",
				venueId, zoneId, String.valueOf(number), number);
		}
		return zoneId;
	}

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 추가는 대표 전용이 아니라서 담당자 토큰으로 보낸다. */
	private ResultActions add(Long performanceId, LocalDateTime startAt) throws Exception {
		return addBody(performanceId, "{\"startAt\":\"" + REQUEST_FORMAT.format(startAt) + "\"}");
	}

	private ResultActions addBody(Long performanceId, String body) throws Exception {
		return mockMvc.perform(post(URL, performanceId)
			.header("Authorization", token(AccountType.SELLER, "STAFF", MY_BUSINESS))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	/** 오늘부터 days 일 뒤 18:00. 겹침 확인이 초 단위에 흔들리지 않게 정각을 쓴다. */
	private LocalDateTime daysLater(int days) {
		return LocalDateTime.now().plusDays(days).withHour(18).withMinute(0).withSecond(0).withNano(0);
	}

	/** 러닝타임 120분 공연. 모든 구역은 UNSET 이고 판매 필드는 비워 둔다. */
	private Long insertPerformance(long businessId, String status, String title) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?, 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, title, status);
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, assignment_status)
			SELECT ?, z.id, 'UNSET' FROM venue_zone z WHERE z.venue_id = ?
			""", performanceId, venueId);
		return performanceId;
	}

	private Long draft() {
		return insertPerformance(MY_BUSINESS, "DRAFT", "내 작성 중 공연");
	}

	/** 판매 일시를 주고 A구역은 R 석(100,000원), B구역은 의도적 미판매로 둔 공개 공연. */
	private Long published(LocalDateTime saleOpenAt, LocalDateTime saleCloseAt) {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED", "내 공개 공연");
		setSale(performanceId, saleOpenAt, saleCloseAt);
		Long gradeId = jdbc.queryForObject(
			"INSERT INTO seat_grade (performance_id, name, price) VALUES (?, 'R', 100000) RETURNING id",
			Long.class, performanceId);
		setZone(performanceId, saleZoneId, "ASSIGNED", gradeId);
		setZone(performanceId, unsoldZoneId, "INTENTIONALLY_UNSOLD", null);
		return performanceId;
	}

	/** 판매가 이미 열려서 회차를 추가하면 바로 좌석을 만드는 공개 공연. */
	private Long publishedOnSale() {
		return published(LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(60));
	}

	private void setSale(Long performanceId, LocalDateTime saleOpenAt, LocalDateTime saleCloseAt) {
		jdbc.update("""
			UPDATE performance
			SET sale_open_at = ?, sale_close_at = ?, max_per_person = 4, receive_type = 'ONSITE', booking_fee = 1000
			WHERE id = ?
			""", Timestamp.valueOf(saleOpenAt), Timestamp.valueOf(saleCloseAt), performanceId);
	}

	private void setZone(Long performanceId, Long zoneId, String status, Long gradeId) {
		jdbc.update("UPDATE zone_grade SET assignment_status = ?, grade_id = ? WHERE performance_id = ? AND zone_id = ?",
			status, gradeId, performanceId, zoneId);
	}

	private Long insertSchedule(Long performanceId, LocalDateTime startAt, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(startAt), status);
	}

	private void insertPostponement(Long scheduleId, LocalDateTime oldStartAt, LocalDateTime newStartAt) {
		jdbc.update("""
			INSERT INTO schedule_postponement (schedule_id, old_start_at, new_start_at, reason, free_cancel_until,
			                                   changed_by, created_at)
			VALUES (?, ?, ?, '테스트', ?, 1, NOW())
			""", scheduleId, Timestamp.valueOf(oldStartAt),
			newStartAt == null ? null : Timestamp.valueOf(newStartAt), Timestamp.valueOf(oldStartAt));
	}

	private int scheduleCount(Long performanceId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM performance_schedule WHERE performance_id = ?", Integer.class,
			performanceId);
	}

	private int seatCount(Long performanceId) {
		return jdbc.queryForObject("""
			SELECT COUNT(*) FROM schedule_seat
			WHERE schedule_id IN (SELECT id FROM performance_schedule WHERE performance_id = ?)
			""", Integer.class, performanceId);
	}

	/** 막히면 코드와 상태로 응답하고 회차도 좌석도 늘지 않아야 한다. */
	private ResultActions assertRejected(Long performanceId, LocalDateTime startAt, int httpStatus, String code)
		throws Exception {
		int schedulesBefore = scheduleCount(performanceId);
		int seatsBefore = seatCount(performanceId);
		ResultActions result = add(performanceId, startAt)
			.andExpect(status().is(httpStatus))
			.andExpect(jsonPath("$.code").value(code));
		assertThat(scheduleCount(performanceId)).isEqualTo(schedulesBefore);
		assertThat(seatCount(performanceId)).isEqualTo(seatsBefore);
		return result;
	}

	@Test
	@DisplayName("작성 중 공연에 담당자가 회차를 추가하면 201 ON_SALE 이고, 판매 오픈이 지났어도 좌석은 만들지 않는다")
	void addToDraft() throws Exception {
		Long performanceId = draft();
		setSale(performanceId, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(60));
		LocalDateTime startAt = daysLater(30);

		add(performanceId, startAt)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.scheduleId").isNumber())
			.andExpect(jsonPath("$.data.performanceId").value(performanceId))
			.andExpect(jsonPath("$.data.startAt").value(REQUEST_FORMAT.format(startAt)))
			.andExpect(jsonPath("$.data.status").value("ON_SALE"))
			.andExpect(jsonPath("$.data.seatsGeneratedAt").isEmpty());

		Map<String, Object> saved = jdbc.queryForMap(
			"SELECT start_at, status, seats_generated_at FROM performance_schedule WHERE performance_id = ?", performanceId);
		assertThat(saved.get("start_at")).isEqualTo(Timestamp.valueOf(startAt));
		assertThat(saved.get("status")).isEqualTo("ON_SALE");
		assertThat(saved.get("seats_generated_at")).isNull();
		assertThat(seatCount(performanceId)).isZero();
	}

	@Test
	@DisplayName("공개 공연은 판매 오픈까지 24시간보다 더 남았으면 좌석을 만들지 않고, 구역이 UNSET 이어도 추가된다")
	void publishedBeforeGenerationTime() throws Exception {
		Long performanceId = published(LocalDateTime.now().plusHours(24).plusMinutes(10), LocalDateTime.now().plusDays(60));
		setZone(performanceId, unsoldZoneId, "UNSET", null);

		add(performanceId, daysLater(30))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.seatsGeneratedAt").isEmpty());

		assertThat(scheduleCount(performanceId)).isEqualTo(1);
		assertThat(seatCount(performanceId)).isZero();
	}

	@Test
	@DisplayName("공개 공연의 판매 오픈 24시간 전부터는 회차를 추가하며 좌석을 바로 만든다 (판매 구역은 가격 복사, 미판매 구역은 BLOCKED)")
	void publishedWithinGenerationTime() throws Exception {
		Long performanceId = published(LocalDateTime.now().plusHours(24).minusMinutes(10), LocalDateTime.now().plusDays(60));

		add(performanceId, daysLater(30))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.seatsGeneratedAt").isNotEmpty());

		assertThat(jdbc.queryForObject(
			"SELECT seats_generated_at IS NOT NULL FROM performance_schedule WHERE performance_id = ?", Boolean.class,
			performanceId)).isTrue();
		assertThat(jdbc.queryForList("""
			SELECT zone_id || ':' || status || ':' || COALESCE(price::text, '-') || ':' || COUNT(*)
			FROM schedule_seat
			WHERE schedule_id IN (SELECT id FROM performance_schedule WHERE performance_id = ?)
			GROUP BY zone_id, status, price
			""", String.class, performanceId))
			.containsExactlyInAnyOrder(saleZoneId + ":AVAILABLE:100000:3", unsoldZoneId + ":BLOCKED:-:2");
	}

	@Test
	@DisplayName("다른 사업체의 공연은 취소 상태여도 없는 공연과 같은 404 PERFORMANCE000 이고, 예매자는 403, 토큰이 없으면 401이다")
	void permissions() throws Exception {
		Long others = insertPerformance(OTHER_BUSINESS, "DRAFT", "남의 공연");
		Long othersCanceled = insertPerformance(OTHER_BUSINESS, "CANCELED", "남의 취소 공연");

		assertRejected(others, daysLater(30), 404, "PERFORMANCE000");
		// 취소 409 보다 404 가 먼저 나와야 남의 공연 상태가 드러나지 않는다.
		assertRejected(othersCanceled, daysLater(30), 404, "PERFORMANCE000");
		add(Long.MAX_VALUE, daysLater(30)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERFORMANCE000"));

		Long mine = draft();
		String body = "{\"startAt\":\"" + REQUEST_FORMAT.format(daysLater(30)) + "\"}";
		mockMvc.perform(post(URL, mine).header("Authorization", token(AccountType.USER, null, null))
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(URL, mine).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
		assertThat(scheduleCount(mine)).isZero();
	}

	@Test
	@DisplayName("지난 시각·시각 누락은 400, 취소된 공연은 409 PERFORMANCE002, 사용 중지된 공연장은 400 VENUE001 이다")
	void rejectsRequestAndState() throws Exception {
		Long performanceId = draft();
		assertRejected(performanceId, LocalDateTime.now().minusMinutes(1), 400, "COMMON000");
		addBody(performanceId, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		assertThat(scheduleCount(performanceId)).isZero();

		assertRejected(insertPerformance(MY_BUSINESS, "CANCELED", "내 취소 공연"), daysLater(30), 409, "PERFORMANCE002");

		jdbc.update("UPDATE venue SET status = 'INACTIVE' WHERE id = ?", venueId);
		assertRejected(performanceId, daysLater(30), 400, "VENUE001");
	}

	@Test
	@DisplayName("판매 오픈이 설정되어 있으면 작성 중·공개 모두 오픈과 같거나 이른 회차는 400 PERFORMANCE407 이다")
	void rejectsScheduleBeforeSaleOpen() throws Exception {
		LocalDateTime saleOpenAt = daysLater(10);

		Long draft = draft();
		setSale(draft, saleOpenAt, daysLater(60));
		assertRejected(draft, saleOpenAt, 400, "PERFORMANCE407");

		Long published = published(saleOpenAt, daysLater(60));
		assertRejected(published, saleOpenAt.minusDays(1), 400, "PERFORMANCE407");
		add(published, saleOpenAt.plusMinutes(1)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("작성 중 공연은 3시간 이내 회차를 400 PERFORMANCE009 로 막지만, 공개 공연은 지금보다 뒤면 추가된다")
	void draftNeedsLeadTime() throws Exception {
		Long draft = draft();
		assertRejected(draft, LocalDateTime.now().plusHours(3).minusMinutes(5), 400, "PERFORMANCE009");
		add(draft, LocalDateTime.now().plusHours(3).plusMinutes(5)).andExpect(status().isCreated());

		jdbc.update("DELETE FROM performance_schedule WHERE performance_id = ?", draft);
		add(publishedOnSale(), LocalDateTime.now().plusHours(1)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("공개 공연은 회차가 모두 시작했으면 409 PERFORMANCE010(판매가 끝났어도 이것이 먼저), 판매만 끝났으면 400 PERFORMANCE405 이다")
	void rejectsFinishedOrSaleClosed() throws Exception {
		Long allStarted = published(LocalDateTime.now().minusDays(20), LocalDateTime.now().minusDays(5));
		insertSchedule(allStarted, LocalDateTime.now().minusDays(3), "ON_SALE");
		insertSchedule(allStarted, daysLater(40), "CANCELED");
		assertRejected(allStarted, daysLater(30), 409, "PERFORMANCE010");

		Long saleClosed = published(LocalDateTime.now().minusDays(20), LocalDateTime.now().minusMinutes(1));
		insertSchedule(saleClosed, daysLater(20), "ON_SALE");
		assertRejected(saleClosed, daysLater(30), 400, "PERFORMANCE405");

		// 취소된 회차만 있으면 "모두 시작함"이 아니다.
		Long onlyCanceled = publishedOnSale();
		insertSchedule(onlyCanceled, LocalDateTime.now().minusDays(3), "CANCELED");
		add(onlyCanceled, daysLater(50)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("좌석을 바로 만들 수 없으면(UNSET 구역·판매 구역 없음·복사할 좌석 없음) 400·409 이고 회차도 남지 않는다")
	void seatGenerationFailureRemovesSchedule() throws Exception {
		Long unset = publishedOnSale();
		setZone(unset, unsoldZoneId, "UNSET", null);
		assertRejected(unset, daysLater(30), 400, "PERFORMANCE403")
			.andExpect(jsonPath("$.message")
				.value("판매 좌석을 바로 만들어야 해서 회차를 추가할 수 없습니다. 설정하지 않은 구역이 있습니다: B구역"));

		Long noAssigned = publishedOnSale();
		setZone(noAssigned, saleZoneId, "INTENTIONALLY_UNSOLD", null);
		assertRejected(noAssigned, daysLater(31), 400, "PERFORMANCE404");

		Long noSeats = publishedOnSale();
		jdbc.update("DELETE FROM venue_seat WHERE venue_id = ?", venueId);
		assertRejected(noSeats, daysLater(32), 409, "PERFORMANCE503");
	}

	// ---- 시간 겹침 ----

	@Test
	@DisplayName("앞뒤 회차와는 러닝타임 + 1시간을 비워야 한다. 딱 맞으면 추가되고 1분이라도 겹치면 409 PERFORMANCE007 이다")
	void overlapBoundary() throws Exception {
		Long existing = draft();
		LocalDateTime startAt = daysLater(30);
		insertSchedule(existing, startAt, "ON_SALE");
		Long target = insertPerformance(MY_BUSINESS, "DRAFT", "추가할 공연");

		assertRejected(target, startAt.plusMinutes(179), 409, "PERFORMANCE007");
		assertRejected(target, startAt.minusMinutes(179), 409, "PERFORMANCE007");
		add(target, startAt.plusMinutes(180)).andExpect(status().isCreated());
		add(target, startAt.minusMinutes(180)).andExpect(status().isCreated());
		// 같은 공연의 회차끼리도 겹치면 막는다.
		assertRejected(target, startAt.plusMinutes(181), 409, "PERFORMANCE007");
	}

	@Test
	@DisplayName("겹친 회차가 내 것이면 공연명·일시·사용 종료 시각을 알려 주고, 다른 판매자의 회차는 알려 주지 않는다")
	void overlapMessage() throws Exception {
		Long target = insertPerformance(MY_BUSINESS, "DRAFT", "추가할 공연");
		LocalDateTime startAt = daysLater(30);
		Long others = insertPerformance(OTHER_BUSINESS, "DRAFT", "남의 비밀 공연");
		insertSchedule(others, startAt, "ON_SALE");

		assertRejected(target, startAt, 409, "PERFORMANCE007")
			.andExpect(jsonPath("$.message").value(OVERLAP_MESSAGE));

		insertSchedule(draft(), startAt.plusHours(1), "ON_SALE");
		assertRejected(target, startAt, 409, "PERFORMANCE007")
			.andExpect(jsonPath("$.message").value("같은 공연장의 다른 회차와 시간이 겹칩니다: 내 작성 중 공연 "
				+ MESSAGE_FORMAT.format(startAt.plusHours(1)) + " (22:00까지 사용)"));

		// 사용이 다음 날까지 이어지면 끝나는 날짜도 적는다.
		LocalDateTime lateNight = daysLater(40).withHour(23);
		insertSchedule(draft(), lateNight, "ON_SALE");
		assertRejected(target, lateNight, 409, "PERFORMANCE007")
			.andExpect(jsonPath("$.message").value("같은 공연장의 다른 회차와 시간이 겹칩니다: 내 작성 중 공연 "
				+ MESSAGE_FORMAT.format(lateNight) + " (" + MESSAGE_FORMAT.format(lateNight.plusHours(3)) + "까지 사용)"));
	}

	@Test
	@DisplayName("취소 회차·취소 공연의 회차·새 일정이 없는 연기 회차는 겹침으로 세지 않고, 새 일정이 정해진 연기 회차는 센다")
	void overlapExclusions() throws Exception {
		LocalDateTime startAt = daysLater(30);
		Long existing = draft();
		insertSchedule(existing, startAt, "CANCELED");
		insertSchedule(insertPerformance(MY_BUSINESS, "CANCELED", "내 취소 공연"), startAt, "ON_SALE");
		insertPostponement(insertSchedule(existing, startAt, "POSTPONED"), startAt, null);

		add(insertPerformance(MY_BUSINESS, "DRAFT", "추가할 공연"), startAt).andExpect(status().isCreated());

		LocalDateTime decided = daysLater(40);
		insertPostponement(insertSchedule(existing, decided, "POSTPONED"), startAt, decided);
		assertRejected(insertPerformance(MY_BUSINESS, "DRAFT", "또 추가할 공연"), decided, 409, "PERFORMANCE007");
	}

	// ---- 동시성 ----

	@Test
	@DisplayName("같은 공연에 같은 시각의 회차를 동시에 5번 추가하면 한 번만 201 이고 나머지는 409 이며 회차는 하나만 남는다")
	void concurrentAdd() throws Exception {
		Long performanceId = publishedOnSale();
		LocalDateTime startAt = daysLater(30);

		List<MvcResult> results = runConcurrently(5, i -> () -> add(performanceId, startAt).andReturn());

		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 201).hasSize(1);
		// 나머지는 시간 겹침(PERFORMANCE007)이거나 락을 1초 안에 못 잡은 것(COMMON004)이고 둘 다 409 다.
		assertThat(results).filteredOn(result -> result.getResponse().getStatus() != 201)
			.allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));
		assertThat(scheduleCount(performanceId)).isEqualTo(1);
		assertThat(seatCount(performanceId)).isEqualTo(5);
	}

	@Test
	@DisplayName("다른 작업이 공연 락을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고 회차가 추가되지 않는다")
	void performanceLockHeldByOthers() throws Exception {
		Long performanceId = draft();
		RLock lock = redissonClient.getLock("tickting:lock:performance:" + performanceId);
		// 락은 잡은 스레드의 것이라, 요청을 보내는 테스트 스레드가 아닌 다른 스레드에서 잡아야 기다리게 된다.
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			assertRejected(performanceId, daysLater(30), 409, "COMMON004");
		} finally {
			lock.forceUnlock();
		}
	}
}
