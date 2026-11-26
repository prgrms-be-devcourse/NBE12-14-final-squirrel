package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.domain.payment.facade.RefundRetryFacade;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.infra.repository.PaymentRepository;
import com.squirrel.tickting.domain.payment.service.RefundService;
import com.squirrel.tickting.domain.payment.type.RefundReasonType;
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

	@Autowired
	RefundService refundService;

	@Autowired
	PaymentRepository paymentRepository;

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

	// ---------------------------------------------------------------- 쌓아 둔 환불 보내기 (회차·공연 취소의 일괄 환불)

	@Test
	@DisplayName("쌓아 둔 환불(REQUESTED)은 만들 때 정한 멱등키·금액·사유로 토스에 보내고 완료로 기록하며, 첫 전송이라 재시도 횟수는 0이다")
	void sendsRequestedRefund() {
		Long paymentId = jdbc.queryForObject("""
			INSERT INTO payment (booking_id, order_id, payment_key, method, amount, status, approved_at, created_at)
			VALUES (1, 'BK-REQ-01', 'pay_requested', '카드', ?, 'CANCELED', ?, ?) RETURNING id
			""", Long.class, AMOUNT, sixMinutesAgo, sixMinutesAgo);
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		Refund requested = refundService.request(payment, 1L, AMOUNT, RefundReasonType.PERFORMANCE_CANCEL, "출연자 사정으로 회차 취소", now);
		assertThat(row(requested.getId())).containsEntry("status", "REQUESTED");
		assertThat(gateway.cancelCalls()).isEmpty();

		int sent = refundRetryFacade.sendRequested(now);

		assertThat(sent).isEqualTo(1);
		assertThat(gateway.cancelCalls())
			.containsExactly(new CancelCall("pay_requested", AMOUNT, requested.getIdempotencyKey()));
		Map<String, Object> row = row(requested.getId());
		assertThat(row.get("status")).isEqualTo("DONE");
		assertThat(row.get("retry_count")).isEqualTo(0);
		assertThat(row.get("completed_at")).isNotNull();
	}

	@Test
	@DisplayName("쌓아 둔 환불을 보내다 실패하면 재시도 횟수 0인 실패로 남고, 5분 뒤 다시 보내기가 같은 멱등키로 이어서 보낸다")
	void failedRequestedRefundIsRetried() {
		Long refundId = refund("refund_unavailable_req", "REQUESTED", 0, now);

		refundRetryFacade.sendRequested(now);

		assertThat(row(refundId)).containsEntry("status", "FAILED").containsEntry("retry_count", 0);
		assertThat(refundRetryFacade.retryDue(now.plusMinutes(6))).isEqualTo(1);
		assertThat(gateway.cancelCalls()).extracting(CancelCall::idempotencyKey)
			.containsExactly("key-refund_unavailable_req", "key-refund_unavailable_req");
		assertThat(row(refundId)).containsEntry("retry_count", 1);
	}

	@Test
	@DisplayName("보내기는 쌓아 둔 환불만, 다시 보내기는 실패·요청 중 환불만 보낸다 (서로의 대상을 건드리지 않는다)")
	void sendAndRetryKeepTheirOwnTargets() {
		Long requested = refund("pay_req", "REQUESTED", 0, sixMinutesAgo);
		Long failed = refund("pay_failed", "FAILED", 0, sixMinutesAgo);

		assertThat(refundRetryFacade.retryDue(now)).isEqualTo(1);
		assertThat(row(requested)).containsEntry("status", "REQUESTED");

		assertThat(refundRetryFacade.sendRequested(now)).isEqualTo(1);
		assertThat(row(failed)).containsEntry("status", "DONE");
		assertThat(row(requested)).containsEntry("status", "DONE");
		assertThat(gateway.cancelCalls()).extracting(CancelCall::paymentKey).containsExactly("pay_failed", "pay_req");
	}

	@Test
	@DisplayName("서버 여러 대가 동시에 쌓아 둔 환불을 보내도 환불마다 토스에 한 번만 보낸다")
	void sendsRequestedOnceConcurrently() throws Exception {
		for (int i = 0; i < 3; i++) {
			refund("pay_bulk_" + i, "REQUESTED", 0, now);
		}

		List<Integer> results = runConcurrently(5, i -> () -> refundRetryFacade.sendRequested(now));

		assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
		assertThat(gateway.cancelCalls()).extracting(CancelCall::paymentKey)
			.containsExactlyInAnyOrder("pay_bulk_0", "pay_bulk_1", "pay_bulk_2");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refund WHERE status = 'DONE'", Long.class)).isEqualTo(3);
	}
}
