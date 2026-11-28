package com.squirrel.tickting.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리자의 공연장 사용 중지·재개(ADM-11·12)와 중지가 판매자의 새 판매(공개·회차 추가·등록·공연장 변경)를 막는 것.
 * 계정 종류별 403 은 SecurityConfigTest 가 확인한다. 환불은 추후 구현할 스케줄러가 만든다.
 * 끝나면 전용 공연장과 시드에 없는 사업체 번호의 공연·예매만 지운다.
 */
class AdminVenueApiTest extends IntegrationTestSupport {

	private static final long BUSINESS = 981L;
	private static final long ADMIN_ID = 3L;
	private static final long SELLER_ID = 7L;
	private static final String REASON_PREFIX = "공연장 사용 중지: ";

	@Autowired
	private RedissonClient redissonClient;

	@Autowired
	private StringRedisTemplate redis;

	private Long venueId;

	@BeforeEach
	void insertVenue() {
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('중지 테스트 공연장', '테스트 주소', '서울', 5, 'ACTIVE', NOW(), NOW()) RETURNING id
			""", Long.class);
	}

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id = " + BUSINESS;
		jdbc.update("DELETE FROM booking WHERE business_id = " + BUSINESS);
		jdbc.update("DELETE FROM schedule_postponement WHERE schedule_id IN "
			+ "(SELECT id FROM performance_schedule WHERE performance_id IN (" + mine + "))");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM zone_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_image WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id = " + BUSINESS);
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
	}

	private Cookie admin() {
		return sessionCookie(new CustomUserDetails(ADMIN_ID, AccountType.ADMIN, null, null));
	}

	private Cookie seller() {
		return sessionCookie(new CustomUserDetails(SELLER_ID, AccountType.SELLER, "STAFF", BUSINESS));
	}

	private ResultActions send(Cookie session, String url, Object body) throws Exception {
		var request = post(url).cookie(session);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return mockMvc.perform(request);
	}

	private ResultActions deactivate(String mode) throws Exception {
		return send(admin(), "/api/v1/admin/venues/" + venueId + "/deactivate",
			Map.of("reason", "  화재  ", "mode", mode));
	}

	private ResultActions activate() throws Exception {
		return send(admin(), "/api/v1/admin/venues/" + venueId + "/activate", null);
	}

	private ResultActions publish(Long performanceId) throws Exception {
		return send(seller(), "/api/v1/seller/performances/" + performanceId + "/publish", null);
	}

	private ResultActions addSchedule(Long performanceId) throws Exception {
		return send(seller(), "/api/v1/seller/performances/" + performanceId + "/schedules",
			Map.of("startAt", format(LocalDateTime.now().plusDays(90))));
	}

	private ResultActions register() throws Exception {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("venueId", venueId);
		body.put("cancelPolicyId", policyId);
		body.put("title", "새 공연");
		body.put("genre", "CONCERT");
		body.put("ageLimit", 0);
		body.put("runningTime", 120);
		body.put("posterUrl", "https://cdn.example.com/poster.jpg");
		return send(seller(), "/api/v1/seller/performances", body);
	}

	private static String format(LocalDateTime time) {
		return time.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
	}

	private Long insertPerformance(String status) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '중지 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, NOW(), NOW())
			RETURNING id
			""", Long.class, BUSINESS, venueId, policyId, status);
	}

	/** days 가 음수면 시작 일시가 지난 회차다. */
	private Long insertSchedule(Long performanceId, int days, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, NOW(), NOW()) RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(LocalDateTime.now().plusDays(days)), status);
	}

	private void insertBooking(Long performanceId, Long scheduleId, String status) {
		jdbc.update("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, 1, ?, ?, ?, ?, 'ONSITE', '홍길동', '01012345678', 'buyer@example.com', 1000, 0, 1000,
			        '[]'::jsonb, NOW(), NOW(), NOW())
			""", "T981-" + UUID.randomUUID().toString().substring(0, 8), scheduleId, performanceId, BUSINESS, status);
	}

	private String venueStatus() {
		return jdbc.queryForObject("SELECT status FROM venue WHERE id = ?", String.class, venueId);
	}

	private String performanceStatus(Long id) {
		return jdbc.queryForObject("SELECT status FROM performance WHERE id = ?", String.class, id);
	}

	private Map<String, Object> scheduleRow(Long id) {
		return jdbc.queryForMap("SELECT status, cancel_reason FROM performance_schedule WHERE id = ?", id);
	}

	@Test
	@DisplayName("새 일정만 막으면 기존 회차는 그대로이고 공개·회차 추가·등록이 VENUE001 이며, 재개하면 다시 된다")
	void blocksNewSalesOnly() throws Exception {
		Long published = insertPerformance("PUBLISHED");
		Long scheduleId = insertSchedule(published, 30, "ON_SALE");
		Long draft = insertPerformance("DRAFT");

		deactivate("BLOCK_NEW")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("INACTIVE"))
			.andExpect(jsonPath("$.data.canceledScheduleCount").value(0));

		publish(draft).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VENUE001"));
		addSchedule(draft).andExpect(jsonPath("$.code").value("VENUE001"));
		register().andExpect(jsonPath("$.code").value("VENUE001"));
		assertThat(scheduleRow(scheduleId)).containsEntry("status", "ON_SALE");
		assertThat(performanceStatus(published)).isEqualTo("PUBLISHED");
		deactivate("BLOCK_NEW").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VENUE002"));

		activate().andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));

		// 공연장 확인을 지나 판매 정보가 비었다는 다음 검사에서 막힌다.
		publish(draft).andExpect(jsonPath("$.code").value("PERFORMANCE401"));
		addSchedule(draft).andExpect(status().isCreated());
		activate().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VENUE003"));
	}

	@Test
	@DisplayName("전부 취소: 시작 전·일정 미정 회차만 취소하고, 시작한 회차와 작성 중 공연은 그대로이며, 남은 회차가 없는 공연만 취소한다")
	void cancelsUpcomingSchedulesAndEmptyPerformances() throws Exception {
		Long emptied = insertPerformance("PUBLISHED");
		Long upcoming = insertSchedule(emptied, 30, "ON_SALE");
		// 새 일정이 정해지지 않은 연기 회차는 원래 일시가 지났어도 열린 적이 없다
		Long postponed = insertSchedule(emptied, -2, "POSTPONED");
		Long kept = insertPerformance("PUBLISHED");
		Long started = insertSchedule(kept, -1, "ON_SALE");
		Long paused = insertSchedule(kept, 40, "PAUSED");
		Long draft = insertPerformance("DRAFT");
		Long draftSchedule = insertSchedule(draft, 50, "ON_SALE");
		List.of("PAID", "PARTIAL_CANCELED", "PENDING").forEach(status -> insertBooking(emptied, upcoming, status));
		insertBooking(kept, started, "PAID");
		String cacheKey = "tickting:cache:performance-detail:" + emptied;
		redis.opsForValue().set(cacheKey, "{}");

		deactivate("CANCEL_ALL")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("INACTIVE"))
			.andExpect(jsonPath("$.data.canceledScheduleCount").value(3))
			.andExpect(jsonPath("$.data.canceledPerformanceCount").value(1))
			.andExpect(jsonPath("$.data.refundRequestedCount").value(2));

		assertThat(redis.hasKey(cacheKey)).isFalse();
		assertThat(venueStatus()).isEqualTo("INACTIVE");
		List.of(upcoming, postponed, paused).forEach(id -> assertThat(scheduleRow(id))
			.containsEntry("status", "CANCELED").containsEntry("cancel_reason", REASON_PREFIX + "화재"));
		assertThat(scheduleRow(started)).containsEntry("status", "ON_SALE").containsEntry("cancel_reason", null);
		assertThat(scheduleRow(draftSchedule)).containsEntry("status", "ON_SALE");
		assertThat(performanceStatus(emptied)).isEqualTo("CANCELED");
		assertThat(performanceStatus(kept)).isEqualTo("PUBLISHED");
		assertThat(performanceStatus(draft)).isEqualTo("DRAFT");
		assertThat(jdbc.queryForList("""
			SELECT target_type || ':' || action || ':' || actor_type || ':' || COALESCE(business_id::text, '-')
			  FROM audit_log ORDER BY id
			""", String.class)).containsExactlyInAnyOrder("VENUE:DEACTIVATE:ADMIN:-",
			"SCHEDULE:CANCEL:ADMIN:" + BUSINESS, "SCHEDULE:CANCEL:ADMIN:" + BUSINESS,
			"SCHEDULE:CANCEL:ADMIN:" + BUSINESS, "PERFORMANCE:CANCEL:ADMIN:" + BUSINESS);
	}

	@Test
	@DisplayName("이미 중지한 공연장도 전부 취소할 수 있고, 취소할 회차가 없으면 VENUE004 이며 아무것도 바뀌지 않는다")
	void cancelsAllOnInactiveVenue() throws Exception {
		Long performanceId = insertPerformance("PUBLISHED");
		Long scheduleId = insertSchedule(performanceId, 30, "ON_SALE");
		deactivate("BLOCK_NEW").andExpect(status().isOk());

		deactivate("CANCEL_ALL").andExpect(status().isOk()).andExpect(jsonPath("$.data.canceledScheduleCount").value(1));
		assertThat(scheduleRow(scheduleId)).containsEntry("status", "CANCELED");
		long logs = count("audit_log");

		deactivate("CANCEL_ALL").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VENUE004"));
		assertThat(count("audit_log")).isEqualTo(logs);
	}

	@Test
	@DisplayName("같은 중지가 동시에 여러 번 들어와도 한 번만 성공하고 감사 로그는 한 줄이다")
	void deactivatesOnceConcurrently() throws Exception {
		List<String> results = runConcurrently(5, i -> () -> {
			MvcResult result = deactivate("BLOCK_NEW").andReturn();
			return result.getResponse().getStatus() == 200 ? "OK" : read(result, "$.code");
		});

		assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
		assertThat(results).allMatch(result -> List.of("OK", "VENUE002", "COMMON004").contains(result));
		assertThat(count("audit_log")).isEqualTo(1);
	}

	@Test
	@DisplayName("공연장 락을 다른 작업이 쥐고 있으면 중지·재개·공연 등록·공개·회차 추가가 COMMON004 다")
	void newSalesWaitForVenueLock() throws Exception {
		Long draft = insertPerformance("DRAFT");

		withLock("venue:" + venueId, () -> {
			deactivate("BLOCK_NEW").andExpect(jsonPath("$.code").value("COMMON004"));
			activate().andExpect(jsonPath("$.code").value("COMMON004"));
			register().andExpect(jsonPath("$.code").value("COMMON004"));
			publish(draft).andExpect(jsonPath("$.code").value("COMMON004"));
			addSchedule(draft).andExpect(jsonPath("$.code").value("COMMON004"));
		});

		assertThat(venueStatus()).isEqualTo("ACTIVE");
		assertThat(count("audit_log")).isZero();
	}

	@Test
	@DisplayName("대상 공연의 락을 다른 작업이 쥐고 있으면 전부 취소는 COMMON004 이고 공연장도 그대로다")
	void cancelAllWaitsForPerformanceLock() throws Exception {
		Long performanceId = insertPerformance("PUBLISHED");
		Long scheduleId = insertSchedule(performanceId, 30, "ON_SALE");

		withLock("performance:" + performanceId,
			() -> deactivate("CANCEL_ALL").andExpect(jsonPath("$.code").value("COMMON004")));

		assertThat(venueStatus()).isEqualTo("ACTIVE");
		assertThat(scheduleRow(scheduleId)).containsEntry("status", "ON_SALE");
	}

	@Test
	@DisplayName("전부 취소와 판매자의 새 일정 확정이 동시에 들어와도 중지가 성공했다면 그 회차는 취소 상태다")
	void cancelAllAndPostponementConfirmDoNotCross() throws Exception {
		Long performanceId = insertPerformance("PUBLISHED");
		Long scheduleId = insertSchedule(performanceId, 30, "POSTPONED");
		jdbc.update("""
			INSERT INTO schedule_postponement (schedule_id, old_start_at, new_start_at, reason, free_cancel_until,
			                                   changed_by, created_at)
			VALUES (?, NOW(), NULL, '출연자 사정', NOW() + INTERVAL '14 days', 1, NOW())
			""", scheduleId);
		jdbc.update("""
			UPDATE performance SET sale_open_at = NOW() - INTERVAL '1 day', sale_close_at = NOW() + INTERVAL '10 days'
			 WHERE id = ?
			""", performanceId);

		List<Integer> statuses = runConcurrently(2, i -> () -> (i == 0
			? deactivate("CANCEL_ALL")
			: send(seller(), "/api/v1/seller/schedules/" + scheduleId + "/postpone/confirm",
				Map.of("newStartAt", format(LocalDateTime.now().plusDays(60)))))
			.andReturn().getResponse().getStatus());

		// 둘 중 하나는 반드시 성공하고, 중지가 성공했으면 확정이 먼저였든 거절됐든 회차는 취소다.
		assertThat(statuses).contains(200);
		boolean canceled = "CANCELED".equals(scheduleRow(scheduleId).get("status"));
		assertThat(canceled).isEqualTo("INACTIVE".equals(venueStatus()));
	}

	@Test
	@DisplayName("감사 로그를 저장하지 못하면 공연장 중지와 회차·공연 취소를 모두 되돌린다")
	void rollsBackWhenAuditLogFails() throws Exception {
		Long performanceId = insertPerformance("PUBLISHED");
		Long scheduleId = insertSchedule(performanceId, 30, "ON_SALE");
		jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT test_reject_cancel CHECK (action <> 'CANCEL')");
		try {
			deactivate("CANCEL_ALL").andExpect(status().is4xxClientError());
		} finally {
			jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT test_reject_cancel");
		}

		assertThat(venueStatus()).isEqualTo("ACTIVE");
		assertThat(scheduleRow(scheduleId)).containsEntry("status", "ON_SALE").containsEntry("cancel_reason", null);
		assertThat(performanceStatus(performanceId)).isEqualTo("PUBLISHED");
		assertThat(count("audit_log")).isZero();
	}

	private interface Requests {
		void run() throws Exception;
	}

	/** 락은 스레드마다 따로라서, 락을 쥔 채 다른 스레드에서 요청한다. */
	private void withLock(String name, Requests requests) throws Exception {
		RLock lock = redissonClient.getLock("tickting:lock:" + name);
		lock.lock();
		try {
			CompletableFuture.runAsync(() -> {
				try {
					requests.run();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).get(20, TimeUnit.SECONDS);
		} finally {
			lock.unlock();
		}
	}
}
