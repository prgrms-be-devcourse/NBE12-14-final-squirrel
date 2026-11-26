package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.booking.facade.BookingExpiryFacade;
import com.squirrel.tickting.domain.payment.facade.PaymentReconcileFacade;
import com.squirrel.tickting.domain.payment.gateway.GatewayLookup;
import com.squirrel.tickting.domain.payment.gateway.GatewayPayment;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import com.squirrel.tickting.domain.payment.service.PaymentService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 결제 불일치 보정(doc/part5-operations-area.md 5-2 ②). 스케줄러는 테스트에서 꺼 두고 파사드를 직접 부르며, 시각은 인자로 넘겨서
 * "승인을 시작한 지 5분이 지났다"를 만든다. 예매는 실제 좌석 점유 API, 결제는 실제 결제 준비·승인 API 로 만들고,
 * "승인 응답을 잃은 결제"는 Mock 이 unavailable_ 결제 키에 결과 모름을 돌려주게 해서 실제 흐름 그대로 만든다.
 * 토스 조회 결과는 Mock 에 주문별로 정한다. 정하지 않으면 "승인에 성공한 주문은 결제됨, 그 밖은 결제 안 됨"이다.
 * 기준 설정: 승인 중은 승인 시작 후 5분, 준비는 준비 후 15분부터 조회하고, 토스가 끝나지 않았다고만 답해도 1시간이 지나면 닫는다.
 */
class PaymentReconcileTest extends BookingTestSupport {

	@Autowired
	PaymentReconcileFacade reconcileFacade;

	@Autowired
	MockPaymentGateway gateway;

	@Autowired
	PaymentService paymentService;

	@Autowired
	BookingExpiryFacade bookingExpiryFacade;

	/** 승인을 시작한 지 5분이 지났고, 예매 점유(7분)도 지난 시각. */
	private final LocalDateTime later = now.plusMinutes(10);

	@BeforeEach
	void resetPayments() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
		gateway.reset();
	}

	@AfterEach
	void cleanUpPayments() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
	}

	private record Order(Long bookingId, String orderId, int amount) {
	}

	/** 좌석을 점유하고 결제를 준비한다. 좌석은 fromIndex 번째부터 seatCount 개다. */
	private Order prepareOrder(User user, Show show, int fromIndex, int seatCount) throws Exception {
		MvcResult booked = book(user.session(), show.scheduleId(), show.seatIds().subList(fromIndex, fromIndex + seatCount))
			.andExpect(status().isCreated()).andReturn();
		Long bookingId = Long.parseLong(read(booked, "$.data.bookingId"));
		String orderId = prepareAgain(user, bookingId);
		return new Order(bookingId, orderId, amountOf(bookingId));
	}

	private Order prepareOrder(User user, Show show, int seatCount) throws Exception {
		return prepareOrder(user, show, 0, seatCount);
	}

	/** 결제를 준비한다. 이전 결제가 승인 중이어도 새 주문 ID 가 만들어진다. */
	private String prepareAgain(User user, Long bookingId) throws Exception {
		MvcResult prepared = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/payments")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"))))
			.andExpect(status().isOk()).andReturn();
		return read(prepared, "$.data.orderId");
	}

	private int amountOf(Long bookingId) {
		return jdbc.queryForObject("SELECT total_amount FROM booking WHERE id = ?", Integer.class, bookingId);
	}

	private ResultActions confirm(User user, String paymentKey, String orderId, int amount) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("paymentKey", paymentKey);
		body.put("orderId", orderId);
		body.put("amount", amount);
		return mockMvc.perform(post("/api/v1/payments/confirm")
			.cookie(user.session())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	/** 승인 요청의 응답을 잃은 결제를 만든다. 결제는 승인 중(PROCESSING)으로 남는다. */
	private Order lostApproval(User user, Show show, int seatCount) throws Exception {
		Order order = prepareOrder(user, show, seatCount);
		confirm(user, "unavailable_1", order.orderId(), order.amount())
			.andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PAYMENT006"));
		assertThat(paymentStatus(order.orderId())).isEqualTo("PROCESSING");
		return order;
	}

	/** 토스에서는 결제된 것으로 조회되게 한다. */
	private void tossPaid(Order order, int tossAmount) {
		gateway.reportLookup(order.orderId(),
			GatewayLookup.paid(new GatewayPayment("pay_real", "카드", LocalDateTime.now().withNano(0)), tossAmount));
	}

	private Long paymentId(String orderId) {
		return jdbc.queryForObject("SELECT id FROM payment WHERE order_id = ?", Long.class, orderId);
	}

	private String paymentStatus(String orderId) {
		return jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
	}

	private String bookingStatus(Long bookingId) {
		return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
	}

	private long activeSeatCount(Long bookingId) {
		return jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE booking_id = ? AND active", Long.class, bookingId);
	}

	private long refundCount() {
		return jdbc.queryForObject("SELECT count(*) FROM refund", Long.class);
	}

	// ---------------------------------------------------------------- 결제됨 + 좌석이 아직 내 것: 예매 확정

	@Test
	@DisplayName("승인 응답을 잃었지만 토스에서 결제됐고 좌석이 아직 내 것이면 결제 DONE, 예매 PAID, 좌석 SOLD 로 확정한다")
	void confirmsWhenPaidAndSeatsStillMine() throws Exception {
		Show show = show(3);
		User user = signup("first");
		Order order = lostApproval(user, show, 2);
		tossPaid(order, order.amount());

		int reconciled = reconcileFacade.reconcileDue(later);

		assertThat(reconciled).isEqualTo(1);
		Map<String, Object> payment = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order.orderId());
		assertThat(payment.get("status")).isEqualTo("DONE");
		// 결제 키는 승인 응답을 잃어서 몰랐던 값이다. 토스 조회로 알게 된 값이 저장된다
		assertThat(payment.get("payment_key")).isEqualTo("pay_real");
		assertThat(payment.get("method")).isEqualTo("카드");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
		for (Long seatId : show.seatIds().subList(0, 2)) {
			assertThat(seatStatus(seatId)).isEqualTo("SOLD");
		}
		assertThat(refundCount()).isZero();
		assertThat(gateway.cancelCalls()).isEmpty();
	}

	// ---------------------------------------------------------------- 결제됨 + 좌석이 이미 풀림: 자동 환불

	@Test
	@DisplayName("토스에서 결제됐는데 예매가 이미 만료돼 좌석이 풀렸으면 받은 돈을 전액 환불(SYSTEM)하고 예매를 취소로 바꾸며 감사 로그를 남긴다")
	void refundsWhenPaidButBookingExpired() throws Exception {
		Show show = show(3);
		User user = signup("first");
		Order order = lostApproval(user, show, 2);
		bookingExpiryFacade.expireOverdue(later, 100);
		assertThat(bookingStatus(order.bookingId())).isEqualTo("EXPIRED");
		tossPaid(order, order.amount());

		int reconciled = reconcileFacade.reconcileDue(later);

		assertThat(reconciled).isEqualTo(1);
		// 결제 DONE 으로 기록한 뒤 전액 환불해서 CANCELED 가 된다
		Map<String, Object> payment = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order.orderId());
		assertThat(payment.get("status")).isEqualTo("CANCELED");
		assertThat(payment.get("payment_key")).isEqualTo("pay_real");
		// 환불 한 건. 토스에 같은 멱등키·전액으로 보냈고 성공으로 기록됐다
		Map<String, Object> refund = jdbc.queryForMap("SELECT * FROM refund");
		assertThat(refund.get("reason_type")).isEqualTo("SYSTEM");
		assertThat(refund.get("amount")).isEqualTo(order.amount());
		assertThat(refund.get("status")).isEqualTo("DONE");
		assertThat(refund.get("booking_id")).isEqualTo(order.bookingId());
		assertThat(gateway.cancelCalls()).hasSize(1);
		assertThat(gateway.cancelCalls().get(0).paymentKey()).isEqualTo("pay_real");
		assertThat(gateway.cancelCalls().get(0).amount()).isEqualTo(order.amount());
		assertThat(gateway.cancelCalls().get(0).idempotencyKey()).isEqualTo(refund.get("idempotency_key"));
		// 만료로 남아 있던 예매는 결제·환불 기록과 맞게 취소가 되고, 좌석은 만료 때 풀린 채로다
		assertThat(bookingStatus(order.bookingId())).isEqualTo("CANCELED");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM audit_log WHERE actor_type = 'SYSTEM' AND action = 'AUTO_REFUND' AND target_type = 'PAYMENT'
			""", Long.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("자동 환불을 토스에 보냈는데 실패하면 환불은 실패(FAILED)로 남아 환불 재시도 작업이 이어서 처리한다")
	void autoRefundFailureLeavesFailedRefund() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = lostApproval(user, show, 1);
		bookingExpiryFacade.expireOverdue(later, 100);
		// 환불 거절을 고르려면 결제 키에 refund_reject_ 접두사가 필요하다
		gateway.reportLookup(order.orderId(), GatewayLookup.paid(
			new GatewayPayment("refund_reject_pay", "카드", LocalDateTime.now().withNano(0)), order.amount()));

		reconcileFacade.reconcileDue(later);

		assertThat(jdbc.queryForObject("SELECT status FROM refund", String.class)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("SELECT fail_reason FROM refund", String.class)).isNotBlank();
		assertThat(paymentStatus(order.orderId())).isEqualTo("CANCELED");
	}

	@Test
	@DisplayName("다른 결제로 이미 결제 완료된 예매의 중복 결제는 그 결제만 환불하고 예매는 결제 완료로 둔다")
	void refundsOnlyDuplicatePayment() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order first = lostApproval(user, show, 1);
		// 사용자가 다시 시도해서 두 번째 결제로 승인에 성공했다
		String secondOrderId = prepareAgain(user, first.bookingId());
		confirm(user, "pay_second", secondOrderId, first.amount()).andExpect(status().isOk());
		assertThat(bookingStatus(first.bookingId())).isEqualTo("PAID");
		// 첫 번째 결제도 사실은 토스에서 승인돼 있었다
		tossPaid(first, first.amount());

		reconcileFacade.reconcileDue(later);

		assertThat(paymentStatus(first.orderId())).isEqualTo("CANCELED");
		assertThat(paymentStatus(secondOrderId)).isEqualTo("DONE");
		assertThat(bookingStatus(first.bookingId())).isEqualTo("PAID");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("SOLD");
		assertThat(refundCount()).isEqualTo(1);
		assertThat(gateway.cancelCalls()).hasSize(1);
	}

	// ---------------------------------------------------------------- 결제 안 됨: 만료 처리

	@Test
	@DisplayName("토스에서 결제되지 않은 승인 중 결제는 FAILED 로 닫고 예매를 바로 만료시켜 좌석을 풀어 준다")
	void closesWhenNotPaid() throws Exception {
		Show show = show(3);
		User user = signup("first");
		// 기본 조회 결과는 "승인에 성공한 주문만 결제됨"이라 응답을 잃은 이 주문은 결제 안 됨이다
		Order order = lostApproval(user, show, 2);

		int reconciled = reconcileFacade.reconcileDue(later);

		assertThat(reconciled).isEqualTo(1);
		assertThat(paymentStatus(order.orderId())).isEqualTo("FAILED");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("EXPIRED");
		assertThat(activeSeatCount(order.bookingId())).isZero();
		for (Long seatId : show.seatIds().subList(0, 2)) {
			assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE");
		}
		assertThat(refundCount()).isZero();
	}

	@Test
	@DisplayName("같은 예매에 진행 중인 다른 결제가 있으면 한 결제가 결제 안 됨으로 닫혀도 예매와 좌석은 그대로 둔다")
	void keepsBookingWhenAnotherPaymentIsLive() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order first = lostApproval(user, show, 1);
		String secondOrderId = prepareAgain(user, first.bookingId());

		reconcileFacade.reconcileDue(later);

		assertThat(paymentStatus(first.orderId())).isEqualTo("FAILED");
		// 두 번째 결제는 준비한 지 얼마 안 돼 대상이 아니고, 예매를 살리고 있다
		assertThat(paymentStatus(secondOrderId)).isEqualTo("READY");
		assertThat(bookingStatus(first.bookingId())).isEqualTo("PENDING");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("HELD");
	}

	// ---------------------------------------------------------------- 모를 때는 아무것도 바꾸지 않는다

	@Test
	@DisplayName("토스 조회가 실패하면(결과를 알 수 없음) 결제·예매·좌석을 하나도 바꾸지 않고 다음 주기에 다시 조회한다")
	void doesNothingWhenLookupFails() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order order = lostApproval(user, show, 2);
		gateway.failLookups(true);

		int reconciled = reconcileFacade.reconcileDue(later);

		assertThat(reconciled).isZero();
		assertThat(gateway.lookedUpOrderIds()).containsExactly(order.orderId());
		assertThat(paymentStatus(order.orderId())).isEqualTo("PROCESSING");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PENDING");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("HELD");

		// 조회가 다시 되면 이어서 확정한다
		gateway.failLookups(false);
		assertThat(reconcileFacade.reconcileDue(later)).isEqualTo(1);
		assertThat(paymentStatus(order.orderId())).isEqualTo("FAILED");
	}

	@Test
	@DisplayName("토스가 아직 끝나지 않았다고 답하면 그대로 두고, 1시간이 지나면 결제되지 않은 것으로 닫는다")
	void undecidedIsKeptThenGivenUp() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = lostApproval(user, show, 1);
		gateway.reportLookup(order.orderId(), GatewayLookup.undecided());

		assertThat(reconcileFacade.reconcileDue(later)).isZero();
		assertThat(paymentStatus(order.orderId())).isEqualTo("PROCESSING");

		assertThat(reconcileFacade.reconcileDue(now.plusHours(2))).isEqualTo(1);
		assertThat(paymentStatus(order.orderId())).isEqualTo("FAILED");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("EXPIRED");
	}

	@Test
	@DisplayName("토스에서 결제된 금액이 우리가 계산한 금액과 다르면 자동으로 처리하지 않고 그대로 둔다 (사람이 확인한다)")
	void doesNothingWhenAmountDiffers() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = lostApproval(user, show, 1);
		tossPaid(order, order.amount() + 1000);

		int reconciled = reconcileFacade.reconcileDue(later);

		assertThat(reconciled).isZero();
		assertThat(paymentStatus(order.orderId())).isEqualTo("PROCESSING");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PENDING");
		assertThat(refundCount()).isZero();
		assertThat(gateway.cancelCalls()).isEmpty();
	}

	// ---------------------------------------------------------------- 지금 진행 중인 결제를 건드리지 않는다

	@Test
	@DisplayName("승인을 시작한 지 5분이 안 됐거나 준비한 지 15분이 안 된 결제는 조회하지 않는다 (지금 응답을 기다리는 승인을 보호한다)")
	void skipsPaymentsNotDue() throws Exception {
		Show show = show(3);
		User user = signup("first");
		Order processing = lostApproval(user, show, 1);
		// 이미 잡힌 좌석은 다시 점유할 수 없어서 두 번째 예매는 다른 좌석(1번째)을 잡는다
		Order ready = prepareOrder(signup("second"), show, 1, 1);

		// 승인을 시작한 지 4분, 준비한 지 14분인 시각
		int reconciled = reconcileFacade.reconcileDue(now.plusMinutes(4));

		assertThat(reconciled).isZero();
		assertThat(gateway.lookedUpOrderIds()).isEmpty();
		assertThat(paymentStatus(processing.orderId())).isEqualTo("PROCESSING");
		assertThat(paymentStatus(ready.orderId())).isEqualTo("READY");
	}

	@Test
	@DisplayName("준비만 하고 결제하지 않은 채 15분이 지난 결제는 토스에 조회하지 않고 FAILED 로 닫고 예매를 만료시킨다")
	void closesAbandonedReadyPaymentWithoutLookup() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);

		int reconciled = reconcileFacade.reconcileDue(now.plusMinutes(20));

		assertThat(reconciled).isEqualTo(1);
		assertThat(gateway.lookedUpOrderIds()).isEmpty();
		assertThat(paymentStatus(order.orderId())).isEqualTo("FAILED");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("EXPIRED");
	}

	@Test
	@DisplayName("보정이 닫은 준비 결제로 뒤늦게 승인 요청이 와도 거절되고 토스에 승인을 보내지 않는다 (돈이 나가지 않는다)")
	void confirmIsRejectedAfterReadyPaymentWasClosed() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);
		reconcileFacade.reconcileDue(now.plusMinutes(20));
		assertThat(paymentStatus(order.orderId())).isEqualTo("FAILED");

		confirm(user, "load_late", order.orderId(), order.amount()).andExpect(status().is4xxClientError());

		assertThat(gateway.confirmedOrderIds()).isEmpty();
		assertThat(paymentStatus(order.orderId())).isEqualTo("FAILED");
	}

	@Test
	@DisplayName("보정 대상은 돈이 걸린 승인 중을 먼저, 그다음 준비를 고른다 (이탈로 쌓인 오래된 준비가 승인 중을 밀어내지 못한다)")
	void processingComesBeforeOlderReady() throws Exception {
		Show show = show(3);
		Order processing = lostApproval(signup("first"), show, 1);
		Order ready = prepareOrder(signup("second"), show, 1, 1);
		// 준비 결제가 승인 중보다 훨씬 오래된 것으로 만든다. 예전처럼 오래된 순서로만 고르면 준비가 먼저 나온다.
		jdbc.update("UPDATE payment SET created_at = created_at - interval '1 hour' WHERE order_id = ?", ready.orderId());

		List<Long> due = paymentService.findReconcileDue(later, later, 1, 1);

		assertThat(due).containsExactly(paymentId(processing.orderId()), paymentId(ready.orderId()));
	}

	// ---------------------------------------------------------------- 서버가 여러 대

	@Test
	@DisplayName("서버 여러 대가 같은 결제를 동시에 보정해도 환불은 한 건만 만들고 토스에도 한 번만 보낸다")
	void concurrentReconcileRefundsOnce() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order order = lostApproval(user, show, 1);
		bookingExpiryFacade.expireOverdue(later, 100);
		tossPaid(order, order.amount());

		List<Integer> results = runConcurrently(5, i -> () -> reconcileFacade.reconcileDue(later));

		assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
		assertThat(gateway.lookedUpOrderIds()).containsExactly(order.orderId());
		assertThat(refundCount()).isEqualTo(1);
		assertThat(gateway.cancelCalls()).hasSize(1);
		assertThat(paymentStatus(order.orderId())).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'AUTO_REFUND'", Long.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("서버 여러 대가 같은 결제를 동시에 보정해도 승인 중 결제는 한 번만 확정되고 좌석은 한 번만 판매 완료가 되며 토스 조회도 한 번이다")
	void concurrentReconcileConfirmsOnce() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order order = lostApproval(user, show, 2);
		tossPaid(order, order.amount());

		List<Integer> results = runConcurrently(5, i -> () -> reconcileFacade.reconcileDue(later));

		assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
		assertThat(gateway.lookedUpOrderIds()).containsExactly(order.orderId());
		assertThat(paymentStatus(order.orderId())).isEqualTo("DONE");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
		assertThat(refundCount()).isZero();
	}
}
