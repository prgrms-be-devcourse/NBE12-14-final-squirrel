package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.domain.payment.facade.RefundRetryFacade;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway.CancelCall;
import com.squirrel.tickting.support.IntegrationTestSupport;
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

/**
 * 환불 재시도 스케줄러가 하는 일. 스케줄러는 테스트에서 꺼 두고 파사드를 직접 부른다.
 * 결제·환불 행은 SQL 로 넣는다. 재시도는 환불 행과 결제 키만 읽어서, 예매부터 만들 필요가 없다.
 * Mock 게이트웨이는 결제 키 접두사로 환불 결과를 고른다. (refund_unavailable_ 결과를 알 수 없음, 그 밖은 성공)
 * 기준 설정: 마지막 시도 후 5분이 지나야 다시 보내고, 실패한 환불은 자동으로 3번까지 다시 보낸다.
 */
class RefundRetryTest extends IntegrationTestSupport {

	private static final int AMOUNT = 156000;

	@Autowired
	RefundRetryFacade refundRetryFacade;

	@Autowired
	MockPaymentGateway gateway;

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private final LocalDateTime sixMinutesAgo = now.minusMinutes(6);

	@BeforeEach
	void reset() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
		gateway.reset();
	}

	@AfterEach
	void cleanUp() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
	}

	/** 결제 한 건과 그 결제의 환불 한 건을 넣고 환불 번호를 돌려준다. */
	private Long refund(String paymentKey, String status, int retryCount, LocalDateTime lastAttemptedAt) {
		Long paymentId = jdbc.queryForObject("""
			INSERT INTO payment (booking_id, order_id, payment_key, method, amount, status, approved_at, created_at)
			VALUES (1, ?, ?, '카드', ?, 'CANCELED', ?, ?) RETURNING id
			""", Long.class, UUID.randomUUID().toString(), paymentKey, AMOUNT, sixMinutesAgo, sixMinutesAgo);
		return jdbc.queryForObject("""
			INSERT INTO refund (payment_id, booking_id, amount, reason_type, idempotency_key, status, retry_count,
			                    fail_reason, requested_at, last_attempted_at)
			VALUES (?, 1, ?, 'USER_CANCEL', ?, ?, ?, ?, ?, ?) RETURNING id
			""", Long.class, paymentId, AMOUNT, "key-" + paymentKey, status, retryCount,
			"FAILED".equals(status) ? "환불 결과를 알 수 없음" : null, sixMinutesAgo, lastAttemptedAt);
	}

	private Map<String, Object> row(Long refundId) {
		return jdbc.queryForMap(
			"SELECT status, retry_count, fail_reason, completed_at, last_attempted_at FROM refund WHERE id = ?",
			refundId);
	}

	@Test
	@DisplayName("실패한 환불은 마지막 시도 후 5분이 지나면 처음과 같은 멱등키·금액으로 다시 보내고 완료로 기록한다")
	void retriesFailedRefund() {
		Long refundId = refund("pay_ok", "FAILED", 0, sixMinutesAgo);

		int retried = refundRetryFacade.retryDue(now);

		assertThat(retried).isEqualTo(1);
		assertThat(gateway.cancelCalls()).containsExactly(new CancelCall("pay_ok", AMOUNT, "key-pay_ok"));
		Map<String, Object> row = row(refundId);
		assertThat(row.get("status")).isEqualTo("DONE");
		assertThat(row.get("retry_count")).isEqualTo(1);
		assertThat(row.get("fail_reason")).isNull();
		assertThat(row.get("completed_at")).isNotNull();
	}

	@Test
	@DisplayName("마지막 시도 후 5분이 안 됐거나 자동 재시도를 3번 쓴 실패 환불, 응답을 기다리는 중인 환불은 보내지 않는다")
	void skipsRefundsNotDue() {
		Long recent = refund("pay_recent", "FAILED", 0, now.minusMinutes(1));
		Long exhausted = refund("pay_exhausted", "FAILED", 3, sixMinutesAgo);
		Long waiting = refund("pay_waiting", "PROCESSING", 0, now.minusSeconds(10));

		int retried = refundRetryFacade.retryDue(now);

		assertThat(retried).isZero();
		assertThat(gateway.cancelCalls()).isEmpty();
		assertThat(row(recent).get("retry_count")).isEqualTo(0);
		assertThat(row(exhausted)).containsEntry("status", "FAILED").containsEntry("retry_count", 3);
		assertThat(row(waiting)).containsEntry("status", "PROCESSING").containsEntry("retry_count", 0);
	}

	@Test
	@DisplayName("다시 보내도 실패하면 횟수를 올려 실패로 남기고, 3번을 다 쓰면 더 보내지 않고 관리자 처리로 넘긴다")
	void stopsAfterMaxRetries() {
		Long refundId = refund("refund_unavailable_pay", "FAILED", 2, sixMinutesAgo);

		refundRetryFacade.retryDue(now);

		Map<String, Object> row = row(refundId);
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("retry_count")).isEqualTo(3);
		assertThat((String) row.get("fail_reason")).startsWith("환불 결과를 알 수 없음");
		assertThat(gateway.cancelCalls()).hasSize(1);

		int retriedLater = refundRetryFacade.retryDue(now.plusMinutes(6));

		assertThat(retriedLater).isZero();
		assertThat(gateway.cancelCalls()).hasSize(1);
	}

	@Test
	@DisplayName("요청 중으로 5분 넘게 남은 환불은 돈이 나갔는지 모르므로 재시도 횟수와 상관없이 같은 멱등키로 다시 보내 확정한다")
	void confirmsStaleProcessingRefund() {
		Long refundId = refund("pay_stale", "PROCESSING", 3, sixMinutesAgo);

		refundRetryFacade.retryDue(now);

		assertThat(gateway.cancelCalls()).containsExactly(new CancelCall("pay_stale", AMOUNT, "key-pay_stale"));
		assertThat(row(refundId)).containsEntry("status", "DONE").containsEntry("retry_count", 4);
	}

	@Test
	@DisplayName("서버 여러 대가 동시에 재시도해도 같은 환불은 토스에 한 번만 보낸다")
	void retriesOnceConcurrently() throws Exception {
		Long refundId = refund("pay_concurrent", "FAILED", 0, sixMinutesAgo);
		int threads = 5;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return refundRetryFacade.retryDue(now);
				}));
			}
			start.countDown();
			int retried = 0;
			for (Future<Integer> result : results) {
				retried += result.get();
			}

			assertThat(retried).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
		assertThat(gateway.cancelCalls()).hasSize(1);
		assertThat(row(refundId)).containsEntry("status", "DONE").containsEntry("retry_count", 1);
	}
}
