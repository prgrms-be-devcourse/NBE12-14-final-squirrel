package com.squirrel.tickting.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway.CancelCall;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 관리자 환불 재처리(ADM-06). 결제·환불 행은 SQL 로 넣는다. 재처리는 환불 행과 결제 키만 읽는다.
 * Mock 게이트웨이는 결제 키 접두사로 환불 결과를 고른다. (refund_reject_ 거절, 그 밖은 성공)
 * 기준 설정: 토스 멱등키는 처음 요청한 날부터 15일 유효 (app.payment.refund-retry.idempotency-key-validity)
 */
class AdminRefundRetryApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/admin/refunds/{refundId}/retry";
	private static final Long ADMIN_ID = 7L;
	private static final int AMOUNT = 156000;

	@Autowired
	MockPaymentGateway gateway;

	private final LocalDateTime now = LocalDateTime.now().withNano(0);

	@BeforeEach
	void reset() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
		jdbc.update("DELETE FROM audit_log WHERE target_type = 'REFUND'");
		gateway.reset();
	}

	@AfterEach
	void cleanUp() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
		jdbc.update("DELETE FROM audit_log WHERE target_type = 'REFUND'");
	}

	@SuppressWarnings("removal")
	private Cookie adminToken() {
		return sessionCookie(new CustomUserDetails(ADMIN_ID, AccountType.ADMIN, null, null));
	}

	private ResultActions retry(Long refundId) throws Exception {
		return mockMvc.perform(post(URL, refundId).cookie(adminToken()));
	}

	/** 결제 한 건과 그 결제의 환불 한 건을 넣고 환불 번호를 돌려준다. 멱등키는 "key-" + 결제 키다. */
	private Long refund(String paymentKey, String status, int retryCount, LocalDateTime requestedAt) {
		Long paymentId = jdbc.queryForObject("""
			INSERT INTO payment (booking_id, order_id, payment_key, method, amount, status, approved_at, created_at)
			VALUES (1, ?, ?, '카드', ?, 'CANCELED', ?, ?) RETURNING id
			""", Long.class, UUID.randomUUID().toString(), paymentKey, AMOUNT, requestedAt, requestedAt);
		return jdbc.queryForObject("""
			INSERT INTO refund (payment_id, booking_id, amount, reason_type, idempotency_key, status, retry_count,
			                    fail_reason, requested_at, last_attempted_at)
			VALUES (?, 1, ?, 'USER_CANCEL', ?, ?, ?, ?, ?, ?) RETURNING id
			""", Long.class, paymentId, AMOUNT, "key-" + paymentKey, status, retryCount,
			"FAILED".equals(status) ? "환불 결과를 알 수 없음" : null, requestedAt, requestedAt);
	}

	private Map<String, Object> auditLog(Long refundId) {
		return jdbc.queryForMap("""
			SELECT actor_type, actor_id, action, before_value->>'retryCount' AS before_count,
			       after_value->>'status' AS after_status, after_value->>'retryCount' AS after_count
			FROM audit_log WHERE target_type = 'REFUND' AND target_id = ?
			""", refundId);
	}

	@Test
	@DisplayName("실패한 환불을 다시 보내면 처음과 같은 멱등키·금액으로 한 번 보내고 완료로 기록하며, 처리한 관리자를 감사 로그에 남긴다")
	void retriesFailedRefund() throws Exception {
		Long refundId = refund("pay_ok", "FAILED", 3, now.minusDays(1));

		retry(refundId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("DONE"))
			.andExpect(jsonPath("$.data.retryCount").value(4))
			.andExpect(jsonPath("$.data.failReason").doesNotExist())
			.andExpect(jsonPath("$.data.completedAt").exists());

		assertThat(gateway.cancelCalls()).containsExactly(new CancelCall("pay_ok", AMOUNT, "key-pay_ok"));
		assertThat(auditLog(refundId))
			.containsEntry("actor_type", "ADMIN")
			.containsEntry("actor_id", ADMIN_ID)
			.containsEntry("action", "RETRY")
			.containsEntry("before_count", "3")
			.containsEntry("after_status", "DONE")
			.containsEntry("after_count", "4");
	}

	@Test
	@DisplayName("토스가 또 거절하면 200 으로 답하고 실패 사유와 늘어난 횟수를 기록해 목록에 남기며, 감사 로그도 남긴다")
	void recordsFailureAgain() throws Exception {
		Long refundId = refund("refund_reject_pay", "FAILED", 1, now.minusDays(1));

		retry(refundId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("FAILED"))
			.andExpect(jsonPath("$.data.retryCount").value(2))
			.andExpect(jsonPath("$.data.failReason").value("NOT_CANCELABLE_PAYMENT: 취소할 수 없는 결제입니다."));

		assertThat(gateway.cancelCalls()).hasSize(1);
		assertThat(auditLog(refundId)).containsEntry("after_status", "FAILED").containsEntry("after_count", "2");
	}

	@Test
	@DisplayName("없는 환불은 404, 처리 중·완료된 환불과 요청한 지 15일이 지난 환불은 409 이고 토스를 부르지 않는다")
	void rejectsUnretryableRefunds() throws Exception {
		Long processing = refund("pay_processing", "PROCESSING", 0, now.minusMinutes(1));
		Long done = refund("pay_done", "DONE", 0, now.minusDays(1));
		Long expired = refund("pay_expired", "FAILED", 3, now.minusDays(15).minusMinutes(1));

		retry(999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PAYMENT007"));
		retry(done).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT008"));
		retry(processing).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT009"));
		retry(expired).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT010"));

		assertThat(gateway.cancelCalls()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT retry_count FROM refund WHERE id = ?", Integer.class, expired)).isEqualTo(3);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM audit_log WHERE target_type = 'REFUND'", Long.class)).isZero();
	}

	@Test
	@DisplayName("같은 환불에 재처리 요청이 동시에 여러 번 와도 토스에는 한 번만 보내고 나머지는 409 다")
	void retriesOnceConcurrently() throws Exception {
		Long refundId = refund("pay_concurrent", "FAILED", 3, now.minusDays(1));
		int threads = 5;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return retry(refundId).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}

			assertThat(statuses).containsOnlyOnce(200);
			assertThat(statuses).filteredOn(code -> code != 200).allMatch(code -> code == 409);
		} finally {
			executor.shutdownNow();
		}
		assertThat(gateway.cancelCalls()).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT retry_count FROM refund WHERE id = ?", Integer.class, refundId))
			.isEqualTo(4);
	}
}
