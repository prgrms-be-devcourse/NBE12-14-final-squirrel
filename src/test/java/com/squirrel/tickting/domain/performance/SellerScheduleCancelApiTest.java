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
 * 판매자의 회차 취소(SLR-15). 환불과 예매에 미치는 영향은 BookingCancelApiTest 가 확인한다.
 * 끝나면 전용 공연장과 시드에 없는 사업체 번호의 공연·예매만 지운다.
 */
class SellerScheduleCancelApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/schedules/{scheduleId}/cancel";
	private static final long MY_BUSINESS = 985L;
	private static final long OTHER_BUSINESS = 986L;
	private static final long SELLER_ID = 7L;

	@Autowired
	private RedissonClient redissonClient;

	private Long venueId;

	@BeforeEach
	void insertVenue() {
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('회차 취소 테스트 공연장', '테스트 주소', '서울', 5, 'ACTIVE', NOW(), NOW()) RETURNING id
			""", Long.class);
	}

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM booking WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
	}

	/** 취소는 대표 전용이 아니라서 담당자 토큰으로 보낸다. */
	private ResultActions cancel(Long scheduleId) throws Exception {
		return mockMvc.perform(post(URL, scheduleId)
			.cookie(sessionCookie(new CustomUserDetails(SELLER_ID, AccountType.SELLER, "STAFF", MY_BUSINESS)))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("reason", "  출연자 사정  "))));
	}

	private Long insertPerformance(long businessId, String status) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '회차 취소 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, status);
	}

	/** days 가 음수면 이미 시작한 회차다. */
	private Long insertSchedule(Long performanceId, int days, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, pause_reason, created_at, updated_at)
			VALUES (?, ?, ?, ?, NOW(), NOW()) RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(LocalDateTime.now().plusDays(days)), status,
			"PAUSED".equals(status) ? "점검" : null);
	}

	private void insertBooking(Long performanceId, Long scheduleId, String status) {
		jdbc.update("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, 1, ?, ?, ?, ?, 'ONSITE', '홍길동', '01012345678', 'buyer@example.com', 1000, 0, 1000,
			        '[]'::jsonb, NOW(), NOW(), NOW())
			""", "T985-" + UUID.randomUUID().toString().substring(0, 8), scheduleId, performanceId, MY_BUSINESS, status);
	}

	private Map<String, Object> scheduleRow(Long scheduleId) {
		return jdbc.queryForMap(
			"SELECT status, pause_reason, cancel_reason, updated_at FROM performance_schedule WHERE id = ?", scheduleId);
	}

	private void assertRejected(Long scheduleId, int httpStatus, String code) throws Exception {
		Map<String, Object> before = scheduleRow(scheduleId);
		cancel(scheduleId)
			.andExpect(status().is(httpStatus))
			.andExpect(jsonPath("$.code").value(code));
		assertThat(scheduleRow(scheduleId)).isEqualTo(before);
	}

	@Test
	@DisplayName("담당자가 회차를 취소하면 상태와 사유를 저장하고 감사 로그를 남기며, 환불 대상은 결제 완료·일부 취소 예매 수다")
	void cancelsSchedule() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		Long scheduleId = insertSchedule(performanceId, 30, "PAUSED");
		for (String status : List.of("PAID", "PARTIAL_CANCELED", "PENDING", "CANCELED", "EXPIRED")) {
			insertBooking(performanceId, scheduleId, status);
		}

		cancel(scheduleId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.scheduleId").value(scheduleId))
			.andExpect(jsonPath("$.data.status").value("CANCELED"))
			.andExpect(jsonPath("$.data.refundRequestedCount").value(2));

		assertThat(scheduleRow(scheduleId)).containsEntry("status", "CANCELED")
			.containsEntry("cancel_reason", "출연자 사정").containsEntry("pause_reason", null);
		Map<String, Object> log = jdbc.queryForMap("""
			SELECT actor_type, actor_id, business_id, target_type, target_id, action,
			       before_value ->> 'status' AS before_status, after_value ->> 'status' AS after_status,
			       after_value ->> 'reason' AS reason
			  FROM audit_log
			""");
		assertThat(log).containsEntry("actor_type", "SELLER").containsEntry("actor_id", SELLER_ID)
			.containsEntry("business_id", MY_BUSINESS).containsEntry("target_type", "SCHEDULE")
			.containsEntry("target_id", scheduleId).containsEntry("action", "CANCEL")
			.containsEntry("before_status", "PAUSED").containsEntry("after_status", "CANCELED")
			.containsEntry("reason", "출연자 사정");
		// 요청 안에서는 환불을 만들지 않는다
		assertThat(count("refund")).isZero();
	}

	@Test
	@DisplayName("남의 회차는 404 이고, 취소된 공연·이미 취소한 회차는 409, 시작한 회차는 400 이며 아무것도 바뀌지 않는다")
	void rejects() throws Exception {
		Long others = insertSchedule(insertPerformance(OTHER_BUSINESS, "CANCELED"), 30, "CANCELED");
		Long mine = insertPerformance(MY_BUSINESS, "PUBLISHED");

		// 취소 409 보다 404 가 먼저 나와야 남의 회차 상태가 드러나지 않는다.
		assertRejected(others, 404, "PERFORMANCE008");
		assertRejected(insertSchedule(insertPerformance(MY_BUSINESS, "CANCELED"), 40, "ON_SALE"), 409, "PERFORMANCE002");
		assertRejected(insertSchedule(mine, 50, "CANCELED"), 409, "PERFORMANCE501");
		assertRejected(insertSchedule(mine, -1, "ON_SALE"), 400, "PERFORMANCE502");
		assertThat(count("audit_log")).isZero();
	}

	@Test
	@DisplayName("동시에 여러 번 취소해도 한 번만 성공하고 감사 로그도 한 줄이다")
	void cancelsOnceConcurrently() throws Exception {
		Long scheduleId = insertSchedule(insertPerformance(MY_BUSINESS, "DRAFT"), 30, "ON_SALE");

		List<String> results = runConcurrently(5, i -> () -> {
			MvcResult result = cancel(scheduleId).andReturn();
			return result.getResponse().getStatus() == 200 ? "OK" : read(result, "$.code");
		});

		assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
		assertThat(results).allMatch(result -> List.of("OK", "PERFORMANCE501", "COMMON004").contains(result));
		assertThat(count("audit_log")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 공연의 다른 변경이 락을 쥐고 있으면 409 COMMON004 이고 취소하지 않는다")
	void waitsForPerformanceLock() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long scheduleId = insertSchedule(performanceId, 30, "ON_SALE");
		RLock lock = redissonClient.getLock("tickting:lock:performance:" + performanceId);
		lock.lock();
		try {
			// 락은 스레드마다 따로라서 다른 스레드에서 요청한다
			CompletableFuture.runAsync(() -> {
				try {
					assertRejected(scheduleId, 409, "COMMON004");
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).get(10, TimeUnit.SECONDS);
		} finally {
			lock.unlock();
		}
	}

	@Test
	@DisplayName("감사 로그를 저장하지 못하면 회차 취소도 되돌린다")
	void rollsBackWhenAuditLogFails() throws Exception {
		Long scheduleId = insertSchedule(insertPerformance(MY_BUSINESS, "DRAFT"), 30, "ON_SALE");
		jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT test_reject_cancel CHECK (action <> 'CANCEL')");
		try {
			cancel(scheduleId).andExpect(status().is4xxClientError());
		} finally {
			jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT test_reject_cancel");
		}

		assertThat(scheduleRow(scheduleId)).containsEntry("status", "ON_SALE").containsEntry("cancel_reason", null);
	}
}
