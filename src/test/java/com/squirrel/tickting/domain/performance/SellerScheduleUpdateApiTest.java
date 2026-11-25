package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.UUID;
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
 * 판매자의 회차 수정(SLR-13).
 * 시간 겹침 검사가 공연장의 모든 회차를 보기 때문에, 다른 테스트의 회차가 섞이지 않게 전용 공연장을 만든다.
 * 끝나면 전용 공연장과 시드에 없는 사업체 번호의 공연·예매만 지운다.
 */
class SellerScheduleUpdateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/schedules/{scheduleId}";
	private static final long MY_BUSINESS = 983L;
	private static final long OTHER_BUSINESS = 984L;
	private static final String VENUE_NAME = "회차 수정 테스트 공연장";
	/** 저장하지 않았는지 알 수 있게 회차를 넣을 때 쓰는 과거의 수정 일시. */
	private static final LocalDateTime OLD_UPDATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);
	private static final DateTimeFormatter REQUEST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	private static final DateTimeFormatter MESSAGE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	@Autowired
	private RedissonClient redissonClient;

	private Long venueId;

	@BeforeEach
	void insertVenue() {
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES (?, '테스트 주소', '서울', 5, 'ACTIVE', NOW(), NOW()) RETURNING id
			""", Long.class, VENUE_NAME);
	}

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM booking WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
	}

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	private String body(LocalDateTime startAt) {
		return "{\"startAt\":\"" + REQUEST_FORMAT.format(startAt) + "\"}";
	}

	/** 수정은 대표 전용이 아니라서 담당자 토큰으로 보낸다. */
	private ResultActions update(Long scheduleId, LocalDateTime startAt) throws Exception {
		return mockMvc.perform(put(URL, scheduleId)
			.header("Authorization", token(AccountType.SELLER, "STAFF", MY_BUSINESS))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(startAt)));
	}

	/** 오늘부터 days 일 뒤 18:00. 겹침 확인이 초 단위에 흔들리지 않게 정각을 쓴다. */
	private LocalDateTime daysLater(int days) {
		return LocalDateTime.now().plusDays(days).withHour(18).withMinute(0).withSecond(0).withNano(0);
	}

	/** 러닝타임 120분 공연. 판매 필드는 비워 둔다. */
	private Long insertPerformance(long businessId, String status) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '회차 수정 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, status);
	}

	private Long published(LocalDateTime saleOpenAt, LocalDateTime saleCloseAt) {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		setSale(performanceId, saleOpenAt, saleCloseAt);
		return performanceId;
	}

	/** 판매 중이고 판매 종료가 5일 뒤인 공개 공연. 회차는 그보다 뒤로 옮겨야 판매 종료에 걸리지 않는다. */
	private Long publishedOnSale() {
		return published(LocalDateTime.now().minusDays(1), daysLater(5));
	}

	private void setSale(Long performanceId, LocalDateTime saleOpenAt, LocalDateTime saleCloseAt) {
		jdbc.update("""
			UPDATE performance
			SET sale_open_at = ?, sale_close_at = ?, max_per_person = 4, receive_type = 'ONSITE', booking_fee = 1000
			WHERE id = ?
			""", Timestamp.valueOf(saleOpenAt), Timestamp.valueOf(saleCloseAt), performanceId);
	}

	private Long insertSchedule(Long performanceId, LocalDateTime startAt, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?)
			RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(startAt), status, Timestamp.valueOf(OLD_UPDATED_AT),
			Timestamp.valueOf(OLD_UPDATED_AT));
	}

	/** 결제 제한 시각은 expiresInMinutes 분 뒤이고, 음수면 이미 지났다. */
	private void insertBooking(Long performanceId, Long scheduleId, String status, int expiresInMinutes) {
		jdbc.update("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, 1, ?, ?, ?, ?, 'ONSITE', '홍길동', '01012345678', 'buyer@example.com', 1000, 0, 1000,
			        '[]'::jsonb, ?, NOW(), NOW())
			""", "T983-" + UUID.randomUUID().toString().substring(0, 8), scheduleId, performanceId, MY_BUSINESS,
			status, Timestamp.valueOf(LocalDateTime.now().plusMinutes(expiresInMinutes)));
	}

	private Map<String, Object> scheduleRow(Long scheduleId) {
		return jdbc.queryForMap(
			"SELECT start_at, status, seats_generated_at, updated_at FROM performance_schedule WHERE id = ?", scheduleId);
	}

	/** 막히면 코드와 상태로 응답하고 회차는 그대로여야 한다. */
	private ResultActions assertRejected(Long scheduleId, LocalDateTime startAt, int httpStatus, String code)
		throws Exception {
		Map<String, Object> before = scheduleRow(scheduleId);
		ResultActions result = update(scheduleId, startAt)
			.andExpect(status().is(httpStatus))
			.andExpect(jsonPath("$.code").value(code));
		assertThat(scheduleRow(scheduleId)).isEqualTo(before);
		return result;
	}

	private void assertMoved(Long scheduleId, LocalDateTime startAt) throws Exception {
		update(scheduleId, startAt).andExpect(status().isOk());
		assertThat(scheduleRow(scheduleId).get("start_at")).isEqualTo(Timestamp.valueOf(startAt));
	}

	@Test
	@DisplayName("담당자가 예매 없는 공개 공연의 일시 중지 회차를 수정하면 200 이고, 일시만 바뀌며 상태와 좌석 생성 시각은 그대로다")
	void updatePausedSchedule() throws Exception {
		Long scheduleId = insertSchedule(publishedOnSale(), daysLater(30), "PAUSED");
		LocalDateTime generatedAt = LocalDateTime.of(2026, 2, 1, 10, 0);
		jdbc.update("UPDATE performance_schedule SET seats_generated_at = ? WHERE id = ?",
			Timestamp.valueOf(generatedAt), scheduleId);
		LocalDateTime newStartAt = daysLater(31);

		update(scheduleId, newStartAt)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.scheduleId").value(scheduleId))
			.andExpect(jsonPath("$.data.startAt").value(REQUEST_FORMAT.format(newStartAt)))
			.andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

		Map<String, Object> saved = scheduleRow(scheduleId);
		assertThat(saved.get("start_at")).isEqualTo(Timestamp.valueOf(newStartAt));
		assertThat(saved.get("status")).isEqualTo("PAUSED");
		assertThat(saved.get("seats_generated_at")).isEqualTo(Timestamp.valueOf(generatedAt));
		assertThat((Timestamp) saved.get("updated_at")).isAfter(Timestamp.valueOf(OLD_UPDATED_AT));
	}

	@Test
	@DisplayName("다른 사업체의 회차는 취소 상태여도 없는 회차와 같은 404 PERFORMANCE008 이고, 예매자는 403, 토큰이 없으면 401이다")
	void permissions() throws Exception {
		Long others = insertSchedule(insertPerformance(OTHER_BUSINESS, "DRAFT"), daysLater(30), "ON_SALE");
		Long othersCanceled = insertSchedule(insertPerformance(OTHER_BUSINESS, "CANCELED"), daysLater(40), "CANCELED");

		assertRejected(others, daysLater(31), 404, "PERFORMANCE008");
		// 취소 409 보다 404 가 먼저 나와야 남의 회차 상태가 드러나지 않는다.
		assertRejected(othersCanceled, daysLater(41), 404, "PERFORMANCE008");
		update(Long.MAX_VALUE, daysLater(30)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE008"));

		Long mine = insertSchedule(insertPerformance(MY_BUSINESS, "DRAFT"), daysLater(50), "ON_SALE");
		mockMvc.perform(put(URL, mine).header("Authorization", token(AccountType.USER, null, null))
				.contentType(MediaType.APPLICATION_JSON).content(body(daysLater(51))))
			.andExpect(status().isForbidden());
		mockMvc.perform(put(URL, mine).contentType(MediaType.APPLICATION_JSON).content(body(daysLater(51))))
			.andExpect(status().isUnauthorized());
		assertThat(scheduleRow(mine).get("start_at")).isEqualTo(Timestamp.valueOf(daysLater(50)));
	}

	@Test
	@DisplayName("만료가 아닌 예매(제한 시각이 지난 결제 대기, 전체 취소 포함)가 있는 회차는 409 PERFORMANCE011 이고, 만료 예매나 다른 회차의 예매는 막지 않는다")
	void rejectsScheduleWithBooking() throws Exception {
		Long performanceId = publishedOnSale();
		int day = 10;
		for (String bookingStatus : List.of("PENDING", "PAID", "PARTIAL_CANCELED", "CANCELED")) {
			Long scheduleId = insertSchedule(performanceId, daysLater(day), "ON_SALE");
			insertBooking(performanceId, scheduleId, bookingStatus, 5);
			assertRejected(scheduleId, daysLater(day + 1), 409, "PERFORMANCE011");
			day += 10;
		}
		Long overdue = insertSchedule(performanceId, daysLater(50), "ON_SALE");
		insertBooking(performanceId, overdue, "PENDING", -60);
		assertRejected(overdue, daysLater(51), 409, "PERFORMANCE011");

		Long expiredOnly = insertSchedule(performanceId, daysLater(60), "ON_SALE");
		insertBooking(performanceId, expiredOnly, "EXPIRED", -60);
		assertMoved(expiredOnly, daysLater(61));

		assertMoved(insertSchedule(performanceId, daysLater(70), "ON_SALE"), daysLater(71));
	}

	@Test
	@DisplayName("취소된 공연 409, 취소 회차 409, 연기 회차 409, 이미 시작한 회차 400 이고 일시는 그대로다")
	void rejectsState() throws Exception {
		Long canceledPerformance = insertSchedule(insertPerformance(MY_BUSINESS, "CANCELED"), daysLater(10), "ON_SALE");
		assertRejected(canceledPerformance, daysLater(11), 409, "PERFORMANCE002");

		Long performanceId = publishedOnSale();
		assertRejected(insertSchedule(performanceId, daysLater(20), "CANCELED"), daysLater(21), 409, "PERFORMANCE501")
			.andExpect(jsonPath("$.message").value("취소된 회차는 일시를 바꿀 수 없습니다."));
		assertRejected(insertSchedule(performanceId, daysLater(30), "POSTPONED"), daysLater(31), 409, "PERFORMANCE012");
		assertRejected(insertSchedule(performanceId, LocalDateTime.now().minusHours(1), "ON_SALE"), daysLater(41), 400,
			"PERFORMANCE502")
			.andExpect(jsonPath("$.message").value("이미 시작한 회차는 일시를 바꿀 수 없습니다."));
	}

	@Test
	@DisplayName("지금과 같은 일시를 보내면 예매가 있어도 200 이고 아무것도 저장하지 않아 수정 일시가 그대로다")
	void sameStartAt() throws Exception {
		Long performanceId = publishedOnSale();
		LocalDateTime startAt = daysLater(30);
		Long scheduleId = insertSchedule(performanceId, startAt, "ON_SALE");
		insertBooking(performanceId, scheduleId, "PAID", 5);

		update(scheduleId, startAt)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.startAt").value(REQUEST_FORMAT.format(startAt)))
			.andExpect(jsonPath("$.data.updatedAt").value(REQUEST_FORMAT.format(OLD_UPDATED_AT)));

		assertThat(scheduleRow(scheduleId).get("updated_at")).isEqualTo(Timestamp.valueOf(OLD_UPDATED_AT));
	}

	@Test
	@DisplayName("판매 오픈과 같거나 이른 일시는 400 PERFORMANCE407, 작성 중 공연의 3시간 이내 일시는 400 PERFORMANCE009 이다")
	void rejectsTooEarlyStartAt() throws Exception {
		LocalDateTime saleOpenAt = daysLater(10);
		Long published = insertSchedule(published(saleOpenAt, daysLater(20)), daysLater(30), "ON_SALE");
		assertRejected(published, saleOpenAt, 400, "PERFORMANCE407");

		Long draft = insertSchedule(insertPerformance(MY_BUSINESS, "DRAFT"), daysLater(40), "ON_SALE");
		assertRejected(draft, LocalDateTime.now().plusHours(3).minusMinutes(5), 400, "PERFORMANCE009")
			.andExpect(jsonPath("$.message").value("작성 중인 공연의 회차는 지금부터 3시간 뒤의 일시로만 바꿀 수 있습니다."));
		assertMoved(draft, LocalDateTime.now().plusHours(3).plusMinutes(5).withNano(0));
	}

	@Test
	@DisplayName("옮긴 뒤 판매 종료가 취소되지 않은 마지막 회차보다 늦어지면 400 PERFORMANCE408 이고, 판매 종료와 같으면 200 이다")
	void rejectsSaleCloseAfterLastSchedule() throws Exception {
		LocalDateTime saleCloseAt = daysLater(20);
		Long performanceId = published(LocalDateTime.now().minusDays(1), saleCloseAt);
		insertSchedule(performanceId, daysLater(10), "ON_SALE");
		insertSchedule(performanceId, daysLater(50), "CANCELED");
		Long last = insertSchedule(performanceId, daysLater(30), "ON_SALE");

		assertRejected(last, daysLater(15), 400, "PERFORMANCE408")
			.andExpect(jsonPath("$.message").value("판매 종료 일시는 마지막 회차 시작보다 늦을 수 없습니다. 판매 종료 일시를 "
				+ MESSAGE_FORMAT.format(daysLater(15)) + " 이전으로 먼저 앞당겨 주세요."));
		assertMoved(last, saleCloseAt);
	}

	@Test
	@DisplayName("자기 자신과만 겹치는 이동은 200 이고, 같은 공연장의 다른 회차와 겹치면 409 PERFORMANCE007 이다")
	void overlap() throws Exception {
		Long performanceId = publishedOnSale();
		Long scheduleId = insertSchedule(performanceId, daysLater(30), "ON_SALE");
		insertSchedule(insertPerformance(OTHER_BUSINESS, "DRAFT"), daysLater(31), "ON_SALE");

		assertMoved(scheduleId, daysLater(30).plusHours(1));
		assertRejected(scheduleId, daysLater(31).minusHours(1), 409, "PERFORMANCE007");
	}

	@Test
	@DisplayName("판매가 끝난 공연과 사용이 중지된 공연장의 회차도 바꿀 수 있다")
	void allowsSaleClosedAndInactiveVenue() throws Exception {
		Long saleClosed = published(LocalDateTime.now().minusDays(20), LocalDateTime.now().minusMinutes(1));
		Long scheduleId = insertSchedule(saleClosed, daysLater(30), "ON_SALE");
		jdbc.update("UPDATE venue SET status = 'INACTIVE' WHERE id = ?", venueId);

		assertMoved(scheduleId, daysLater(31));
	}

	// ---- 동시성 ----

	@Test
	@DisplayName("다른 작업이 공연 락을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고 일시가 바뀌지 않는다")
	void performanceLockHeldByOthers() throws Exception {
		Long performanceId = publishedOnSale();
		Long scheduleId = insertSchedule(performanceId, daysLater(30), "ON_SALE");
		RLock lock = redissonClient.getLock("tickting:lock:performance:" + performanceId);
		// 락은 잡은 스레드의 것이라, 요청을 보내는 테스트 스레드가 아닌 다른 스레드에서 잡아야 기다리게 된다.
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			assertRejected(scheduleId, daysLater(31), 409, "COMMON004");
		} finally {
			lock.forceUnlock();
		}
	}

	@Test
	@DisplayName("같은 공연의 두 회차를 같은 시각으로 동시에 옮기면 하나만 200 이고 그 시각의 회차는 하나다")
	void concurrentUpdateToSameTime() throws Exception {
		Long performanceId = publishedOnSale();
		List<Long> scheduleIds = List.of(insertSchedule(performanceId, daysLater(10), "ON_SALE"),
			insertSchedule(performanceId, daysLater(20), "ON_SALE"));
		LocalDateTime target = daysLater(30);

		List<MvcResult> results = runConcurrently(2, i -> () -> update(scheduleIds.get(i), target).andReturn());

		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 200).hasSize(1);
		// 나머지는 시간 겹침(PERFORMANCE007)이거나 락을 1초 안에 못 잡은 것(COMMON004)이고 둘 다 409 다.
		assertThat(results).filteredOn(result -> result.getResponse().getStatus() != 200)
			.allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM performance_schedule WHERE performance_id = ? AND start_at = ?", Integer.class,
			performanceId, Timestamp.valueOf(target))).isEqualTo(1);
	}
}
