package com.squirrel.tickting.domain.performance;

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
 * 판매자의 공연 취소(SLR-16). 취소가 환불·예매에 이어지는지는 BookingCancelApiTest 가 확인한다.
 * 끝나면 전용 공연장과 시드에 없는 사업체 번호의 공연·예매만 지운다.
 */
class SellerPerformanceCancelDeleteApiTest extends IntegrationTestSupport {

	private static final long MY_BUSINESS = 987L;
	private static final long OTHER_BUSINESS = 988L;
	private static final long SELLER_ID = 7L;

	@Autowired
	private RedissonClient redissonClient;

	private Long venueId;

	@BeforeEach
	void insertVenue() {
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('공연 취소 테스트 공연장', '테스트 주소', '서울', 5, 'ACTIVE', NOW(), NOW()) RETURNING id
			""", Long.class);
	}

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM booking WHERE business_id = " + MY_BUSINESS);
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
	}

	/** 취소는 대표 전용이 아니라서 담당자 세션으로 보낸다. */
	private Cookie staff() {
		return sessionCookie(new CustomUserDetails(SELLER_ID, AccountType.SELLER, "STAFF", MY_BUSINESS));
	}

	private ResultActions cancel(Long performanceId) throws Exception {
		return mockMvc.perform(post("/api/v1/seller/performances/{id}/cancel", performanceId)
			.cookie(staff())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("reason", "  제작사 사정  "))));
	}

	private Long insertPerformance(long businessId, String status) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '취소 삭제 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, status);
	}

	/** days 가 음수면 시작 일시가 지난 회차다. */
	private Long insertSchedule(Long performanceId, int days, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, cancel_reason, created_at, updated_at)
			VALUES (?, ?, ?, ?, NOW(), NOW()) RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(LocalDateTime.now().plusDays(days)), status,
			"CANCELED".equals(status) ? "먼저 취소" : null);
	}

	private void insertBooking(Long performanceId, Long scheduleId, String status) {
		jdbc.update("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, 1, ?, ?, ?, ?, 'ONSITE', '홍길동', '01012345678', 'buyer@example.com', 1000, 0, 1000,
			        '[]'::jsonb, NOW(), NOW(), NOW())
			""", "T987-" + UUID.randomUUID().toString().substring(0, 8), scheduleId, performanceId, MY_BUSINESS, status);
	}

	private String performanceStatus(Long performanceId) {
		return jdbc.queryForObject("SELECT status FROM performance WHERE id = ?", String.class, performanceId);
	}

	private Map<String, Object> scheduleRow(Long scheduleId) {
		return jdbc.queryForMap("SELECT status, cancel_reason FROM performance_schedule WHERE id = ?", scheduleId);
	}

	private void assertCancelRejected(Long performanceId, int httpStatus, String code) throws Exception {
		String before = performanceStatus(performanceId);
		cancel(performanceId)
			.andExpect(status().is(httpStatus))
			.andExpect(jsonPath("$.code").value(code));
		assertThat(performanceStatus(performanceId)).isEqualTo(before);
	}

	@Test
	@DisplayName("공연을 취소하면 시작 전·연기 회차만 취소하고 시작한 회차는 그대로이며, 환불 대상은 이번에 취소한 회차의 예매다")
	void cancelsUpcomingAndPostponedSchedules() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		Long upcoming = insertSchedule(performanceId, 30, "ON_SALE");
		// 새 일정이 정해지지 않은 연기 회차는 시작 일시가 지났어도 열린 적이 없다
		Long postponed = insertSchedule(performanceId, -2, "POSTPONED");
		Long started = insertSchedule(performanceId, -1, "ON_SALE");
		Long canceledBefore = insertSchedule(performanceId, 40, "CANCELED");
		List.of("PAID", "PARTIAL_CANCELED", "PENDING", "CANCELED", "EXPIRED")
			.forEach(status -> insertBooking(performanceId, upcoming, status));
		insertBooking(performanceId, postponed, "PAID");
		insertBooking(performanceId, started, "PAID");
		insertBooking(performanceId, canceledBefore, "PAID");

		cancel(performanceId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.performanceId").value(performanceId))
			.andExpect(jsonPath("$.data.status").value("CANCELED"))
			.andExpect(jsonPath("$.data.canceledScheduleCount").value(2))
			.andExpect(jsonPath("$.data.refundRequestedCount").value(3));

		assertThat(performanceStatus(performanceId)).isEqualTo("CANCELED");
		List.of(upcoming, postponed).forEach(id -> assertThat(scheduleRow(id))
			.containsEntry("status", "CANCELED").containsEntry("cancel_reason", "제작사 사정"));
		assertThat(scheduleRow(started)).containsEntry("status", "ON_SALE").containsEntry("cancel_reason", null);
		assertThat(scheduleRow(canceledBefore)).containsEntry("cancel_reason", "먼저 취소");
		Map<String, Object> log = jdbc.queryForMap("""
			SELECT actor_type, actor_id, business_id, target_type, target_id, action,
			       before_value ->> 'status' AS before_status, after_value ->> 'reason' AS reason,
			       jsonb_array_length(after_value -> 'canceledScheduleIds') AS canceled_count
			  FROM audit_log
			""");
		assertThat(log).containsEntry("actor_type", "SELLER").containsEntry("actor_id", SELLER_ID)
			.containsEntry("business_id", MY_BUSINESS).containsEntry("target_type", "PERFORMANCE")
			.containsEntry("target_id", performanceId).containsEntry("action", "CANCEL")
			.containsEntry("before_status", "PUBLISHED").containsEntry("reason", "제작사 사정")
			.containsEntry("canceled_count", 2);
	}

	@Test
	@DisplayName("남의 공연은 404, 취소한 공연·작성 중 공연·회차가 모두 시작한 공연은 409 이고 아무것도 바뀌지 않는다")
	void rejectsCancel() throws Exception {
		Long ended = insertPerformance(MY_BUSINESS, "PUBLISHED");
		Long started = insertSchedule(ended, -1, "ON_SALE");

		// 취소 409 보다 404 가 먼저 나와야 남의 공연 상태가 드러나지 않는다.
		assertCancelRejected(insertPerformance(OTHER_BUSINESS, "CANCELED"), 404, "PERFORMANCE000");
		assertCancelRejected(insertPerformance(MY_BUSINESS, "CANCELED"), 409, "PERFORMANCE002");
		assertCancelRejected(insertPerformance(MY_BUSINESS, "DRAFT"), 409, "PERFORMANCE013");
		assertCancelRejected(ended, 409, "PERFORMANCE010");
		assertThat(scheduleRow(started)).containsEntry("status", "ON_SALE");
		assertThat(count("audit_log")).isZero();
	}

	@Test
	@DisplayName("회차를 모두 따로 취소한 공연은 취소할 수 있고, 동시에 여러 번 취소해도 한 번만 성공한다")
	void cancelsOnceConcurrently() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(performanceId, 30, "CANCELED");

		List<String> results = runConcurrently(5, i -> () -> {
			MvcResult result = cancel(performanceId).andReturn();
			return result.getResponse().getStatus() == 200 ? "OK" : read(result, "$.code");
		});

		assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
		assertThat(results).allMatch(result -> List.of("OK", "PERFORMANCE002", "COMMON004").contains(result));
		assertThat(performanceStatus(performanceId)).isEqualTo("CANCELED");
		assertThat(count("audit_log")).isEqualTo(1);
	}

	@Test
	@DisplayName("감사 로그를 저장하지 못하면 공연과 회차의 취소도 되돌린다")
	void rollsBackWhenAuditLogFails() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		Long scheduleId = insertSchedule(performanceId, 30, "ON_SALE");
		jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT test_reject_cancel CHECK (action <> 'CANCEL')");
		try {
			cancel(performanceId).andExpect(status().is4xxClientError());
		} finally {
			jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT test_reject_cancel");
		}

		assertThat(performanceStatus(performanceId)).isEqualTo("PUBLISHED");
		assertThat(scheduleRow(scheduleId)).containsEntry("status", "ON_SALE").containsEntry("cancel_reason", null);
	}

	@Test
	@DisplayName("같은 공연의 다른 변경이 락을 쥐고 있으면 409 COMMON004 이고 취소하지 않는다")
	void waitsForPerformanceLock() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		RLock lock = redissonClient.getLock("tickting:lock:performance:" + performanceId);
		lock.lock();
		try {
			// 락은 스레드마다 따로라서 다른 스레드에서 요청한다
			CompletableFuture.runAsync(() -> {
				try {
					assertCancelRejected(performanceId, 409, "COMMON004");
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).get(10, TimeUnit.SECONDS);
		} finally {
			lock.unlock();
		}
	}
}
