package com.squirrel.tickting.domain.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 관리자 환불 실패 목록(ADM-05). 결제·예매·환불 행은 SQL 로 넣는다. 목록은 그 행들을 읽기만 한다.
 * 기준 설정: 자동 재시도는 3번까지 (app.payment.refund-retry.max-count)
 */
class AdminRefundListApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/admin/refunds";
	private static final Long ADMIN_ID = 7L;

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private final List<Long> bookingIds = new ArrayList<>();

	@SuppressWarnings("removal")
	private String adminToken() {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(ADMIN_ID, AccountType.ADMIN, null, null));
	}

	@BeforeEach
	void reset() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
	}

	@AfterEach
	void cleanUp() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
		bookingIds.forEach(id -> jdbc.update("DELETE FROM booking WHERE id = ?", id));
	}

	/** 예매·결제와 그 환불 한 건을 넣고 예매번호를 돌려준다. requestedAt 이 이를수록 목록 앞에 온다. */
	private String refund(String status, int retryCount, LocalDateTime requestedAt) {
		String bookingNo = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
		Long bookingId = jdbc.queryForObject("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, 1, 1, 1, 'CANCELED', 'ONSITE', '예매자', '01000000000', 'user@example.com', 154000, 2000,
			        156000, '[]'::jsonb, ?, ?, ?) RETURNING id
			""", Long.class, bookingNo, requestedAt, requestedAt, requestedAt);
		bookingIds.add(bookingId);
		Long paymentId = jdbc.queryForObject("""
			INSERT INTO payment (booking_id, order_id, payment_key, method, amount, status, approved_at, created_at)
			VALUES (?, ?, ?, '카드', 156000, 'CANCELED', ?, ?) RETURNING id
			""", Long.class, bookingId, UUID.randomUUID().toString(), "pay_" + bookingNo, requestedAt, requestedAt);
		jdbc.update("""
			INSERT INTO refund (payment_id, booking_id, amount, reason_type, idempotency_key, status, retry_count,
			                    fail_reason, requested_at, last_attempted_at)
			VALUES (?, ?, 156000, 'USER_CANCEL', ?, ?, ?, ?, ?, ?)
			""", paymentId, bookingId, UUID.randomUUID().toString(), status, retryCount,
			"FAILED".equals(status) ? "환불 결과를 알 수 없음" : null, requestedAt, requestedAt);
		return bookingNo;
	}

	@Test
	@DisplayName("기본 조건이면 자동 재시도를 다 쓴 실패 환불만 오래된 요청부터 보이고, 재시도 중·완료·처리 중인 환불은 빠진다")
	void listsOnlyExhaustedFailures() throws Exception {
		String newer = refund("FAILED", 3, now.minusHours(1));
		String older = refund("FAILED", 4, now.minusHours(2));
		String retrying = refund("FAILED", 1, now.minusHours(3));
		refund("DONE", 3, now.minusHours(4));
		refund("PROCESSING", 3, now.minusHours(5));

		mockMvc.perform(get(URL).header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.content[*].bookingNo").value(Matchers.contains(older, newer)))
			.andExpect(jsonPath("$.data.content[0].status").value("FAILED"))
			.andExpect(jsonPath("$.data.content[0].retryCount").value(4))
			.andExpect(jsonPath("$.data.content[0].failReason").value("환불 결과를 알 수 없음"));

		mockMvc.perform(get(URL).param("exhaustedOnly", "false").header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[*].bookingNo").value(Matchers.contains(retrying, older, newer)));
	}
}
