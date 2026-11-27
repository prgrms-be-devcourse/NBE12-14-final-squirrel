package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.booking.facade.BookingExpiryFacade;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import jakarta.servlet.http.Cookie;
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
 * 결제 승인(BKG-03). 예매는 실제 좌석 점유 API, 결제 준비는 실제 결제 준비 API 로 만들고, 토스 대신 Mock 게이트웨이를 쓴다.
 * 결제 키 접두사로 Mock 의 결과를 고른다. (reject_ 거절, unavailable_ 결과 모름, slow_ 느린 성공)
 * 실제 PostgreSQL·Redis(Testcontainers)에서 확인한다.
 */
class PaymentConfirmApiTest extends BookingTestSupport {

	@Autowired
	MockPaymentGateway gateway;

	@Autowired
	BookingExpiryFacade bookingExpiryFacade;

	@BeforeEach
	void resetPayments() {
		jdbc.execute("TRUNCATE TABLE payment RESTART IDENTITY");
		gateway.reset();
	}

	@AfterEach
	void cleanUpPayments() {
		jdbc.execute("TRUNCATE TABLE payment RESTART IDENTITY");
	}

	/** 점유한 예매와 결제 준비로 받은 주문 정보. */
	private record Order(Long bookingId, String bookingNo, String orderId, int amount) {
	}

	/** 좌석을 점유하고 결제를 준비한다. */
	private Order prepareOrder(User user, Show show, int fromIndex, int seatCount) throws Exception {
		MvcResult booked = book(user.session(), show.scheduleId(), show.seatIds().subList(fromIndex, fromIndex + seatCount))
			.andExpect(status().isCreated())
			.andReturn();
		Long bookingId = Long.parseLong(read(booked, "$.data.bookingId"));
		MvcResult prepared = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/payments")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"))))
			.andExpect(status().isOk())
			.andReturn();
		return new Order(bookingId, read(booked, "$.data.bookingNo"), read(prepared, "$.data.orderId"),
			Integer.parseInt(read(prepared, "$.data.amount")));
	}

	private Order prepareOrder(User user, Show show, int seatCount) throws Exception {
		return prepareOrder(user, show, 0, seatCount);
	}

	private ResultActions confirm(Cookie session, String paymentKey, String orderId, Object amount) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("paymentKey", paymentKey);
		body.put("orderId", orderId);
		body.put("amount", amount);
		return mockMvc.perform(post("/api/v1/payments/confirm")
			.cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String paymentStatus(String orderId) {
		return jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
	}

	private String bookingStatus(Long bookingId) {
		return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
	}

	private LocalDateTime seatHeldUntil(Long seatId) {
		return jdbc.queryForObject("SELECT held_until FROM schedule_seat WHERE id = ?", LocalDateTime.class, seatId);
	}

	// ---------------------------------------------------------------- 정상 흐름

	@Test
	@DisplayName("승인에 성공하면 결제 DONE, 예매 PAID, 좌석 SOLD 가 되고 점유 정보가 지워진다")
	void confirmsPayment() throws Exception {
		Show show = show(3);
		User user = signup("first");
		Order order = prepareOrder(user, show, 2);

		confirm(user.session(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.bookingId").value(order.bookingId()))
			.andExpect(jsonPath("$.data.bookingNo").value(order.bookingNo()))
			.andExpect(jsonPath("$.data.bookingStatus").value("PAID"))
			.andExpect(jsonPath("$.data.paymentStatus").value("DONE"))
			.andExpect(jsonPath("$.data.method").value("카드"))
			.andExpect(jsonPath("$.data.amount").value(312000))
			.andExpect(jsonPath("$.data.approvedAt").isNotEmpty());

		Map<String, Object> payment = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order.orderId());
		assertThat(payment.get("status")).isEqualTo("DONE");
		assertThat(payment.get("payment_key")).isEqualTo("pay_key_ok");
		assertThat(payment.get("method")).isEqualTo("카드");
		assertThat(payment.get("approved_at")).isNotNull();
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
		for (Long seatId : show.seatIds().subList(0, 2)) {
			assertThat(seatStatus(seatId)).isEqualTo("SOLD");
			assertThat(jdbc.queryForObject("SELECT held_by FROM schedule_seat WHERE id = ?", Long.class, seatId)).isNull();
			assertThat(seatHeldUntil(seatId)).isNull();
		}
		// 승인하지 않은 좌석은 그대로다
		assertThat(seatStatus(show.seatIds().get(2))).isEqualTo("AVAILABLE");
		// 결제 완료된 예매 좌석은 계속 활성이라 1인 최대 매수에 들어가고 다시 팔리지 않는다
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE booking_id = ? AND active", Long.class,
			order.bookingId())).isEqualTo(2);
		assertThat(gateway.confirmedOrderIds()).containsExactly(order.orderId());
	}

	@Test
	@DisplayName("점유를 늘린 예매는 승인 결과를 모르는 동안 만료 정리가 돌아도 만료되지 않는다")
	void extendedBookingSurvivesCleanup() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);
		jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", LocalDateTime.now().plusSeconds(30), order.bookingId());
		jdbc.update("UPDATE schedule_seat SET held_until = ? WHERE id = ?", LocalDateTime.now().plusSeconds(30), show.seatIds().get(0));

		confirm(user.session(), "unavailable_1", order.orderId(), order.amount()).andExpect(status().isBadGateway());
		// 1분 뒤에 정리 작업이 돈다고 치면, 늘리지 않았다면 이미 만료됐을 시각이다
		int released = bookingExpiryFacade.expireOverdue(LocalDateTime.now().plusSeconds(60), 100);

		assertThat(released).isZero();
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PENDING");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("HELD");
	}

	// ---------------------------------------------------------------- 검증 (토스를 부르기 전에 거절)

	@Test
	@DisplayName("요청 금액이 서버가 계산한 금액과 다르면 400 PAYMENT004 이고 토스를 부르지 않으며 결제는 준비 상태로 남는다")
	void rejectsAmountMismatch() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);

		confirm(user.session(), "pay_key_ok", order.orderId(), order.amount() - 1)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT004"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
		assertThat(paymentStatus(order.orderId())).isEqualTo("READY");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PENDING");
	}

	@Test
	@DisplayName("결제 제한 시각이 지났으면 409 BOOKING007 이고 토스를 부르지 않으며 결제는 준비 상태로 남는다")
	void rejectsExpiredBooking() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);
		jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", LocalDateTime.now().minusSeconds(1), order.bookingId());

		confirm(user.session(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING007"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
		assertThat(paymentStatus(order.orderId())).isEqualTo("READY");
	}

	@Test
	@DisplayName("판매를 중지한 회차는 점유·결제 준비·승인이 거절되고 토스를 부르지 않으며 점유 시간은 그대로이고, 재개하면 결제된다")
	void rejectsPausedSchedule() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);
		// 승인을 시작하면 점유를 늘리는 상황(남은 시간이 짧음)에서도 중지면 늘리지 않아야 한다.
		LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(30).withNano(0);
		jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", expiresAt, order.bookingId());
		jdbc.update("UPDATE schedule_seat SET held_until = ? WHERE id = ?", expiresAt, show.seatIds().get(0));
		jdbc.update("UPDATE performance_schedule SET status = 'PAUSED' WHERE id = ?", show.scheduleId());

		book(user.session(), show.scheduleId(), List.of(show.seatIds().get(1)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING012"))
			.andExpect(jsonPath("$.message").value("판매가 일시 중지된 회차입니다."));
		mockMvc.perform(post("/api/v1/bookings/" + order.bookingId() + "/payments")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "DELIVERY", "recipientName", "홍길동",
					"recipientPhone", "01012345678", "recipientAddress", "서울"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING012"))
			.andExpect(jsonPath("$.message").value("판매가 일시 중지된 회차라 결제할 수 없습니다."));
		confirm(user.session(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING012"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
		assertThat(paymentStatus(order.orderId())).isEqualTo("READY");
		assertThat(count("payment")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT receive_type FROM booking WHERE id = ?", String.class,
			order.bookingId())).isEqualTo("ONSITE");
		assertThat(jdbc.queryForObject("SELECT expires_at FROM booking WHERE id = ?", LocalDateTime.class,
			order.bookingId())).isEqualTo(expiresAt);
		assertThat(seatHeldUntil(show.seatIds().get(0))).isEqualTo(expiresAt);

		jdbc.update("UPDATE performance_schedule SET status = 'ON_SALE' WHERE id = ?", show.scheduleId());
		confirm(user.session(), "pay_key_ok", order.orderId(), order.amount()).andExpect(status().isOk());
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
	}

	@Test
	@DisplayName("이미 승인한 결제를 다시 승인하면 409 PAYMENT003 이고 토스는 한 번만 불린다")
	void rejectsAlreadyProcessed() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);
		confirm(user.session(), "pay_key_ok", order.orderId(), order.amount()).andExpect(status().isOk());

		confirm(user.session(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PAYMENT003"));

		assertThat(gateway.confirmedOrderIds()).hasSize(1);
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
	}

	@Test
	@DisplayName("없는 주문 ID 와 남의 결제는 같은 404 PAYMENT002 이고 남의 결제는 승인되지 않는다")
	void notMyPaymentIsNotFound() throws Exception {
		Show show = show(1);
		User owner = signup("owner");
		User other = signup("other");
		Order order = prepareOrder(owner, show, 1);

		confirm(other.session(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAYMENT002"));
		confirm(other.session(), "pay_key_ok", "BK-NO-SUCH-ORDER", 1000)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAYMENT002"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
		assertThat(paymentStatus(order.orderId())).isEqualTo("READY");
	}

	// ---------------------------------------------------------------- 토스 결과

	@Test
	@DisplayName("토스가 거절하면 400 PAYMENT005 로 토스의 사유를 알리고, 결제는 FAILED, 예매와 좌석은 점유 상태 그대로이며 새로 준비해 다시 결제할 수 있다")
	void rejectedByGateway() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);

		confirm(user.session(), "reject_card", order.orderId(), order.amount())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT005"))
			.andExpect(jsonPath("$.message").value("카드사에서 결제를 거절했습니다."));

		Map<String, Object> failed = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order.orderId());
		assertThat(failed.get("status")).isEqualTo("FAILED");
		assertThat(failed.get("fail_reason")).isEqualTo("카드사에서 결제를 거절했습니다.");
		// 결제 키는 승인 시작 때 저장돼 실패한 결제에도 남는다. (토스에서 그 시도를 찾아볼 수 있다)
		assertThat(failed.get("payment_key")).isEqualTo("reject_card");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PENDING");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("HELD");

		// 다시 준비하면 새 주문 ID(-02)가 나오고, 그 결제는 승인된다
		MvcResult again = mockMvc.perform(post("/api/v1/bookings/" + order.bookingId() + "/payments")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.orderId").value("BK" + order.bookingNo() + "-02"))
			.andReturn();
		confirm(user.session(), "pay_key_ok", read(again, "$.data.orderId"), order.amount()).andExpect(status().isOk());
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
	}

	@Test
	@DisplayName("승인 여부를 알 수 없으면 502 PAYMENT006 이고, 결제는 승인 중(PROCESSING)으로 남아 같은 결제의 재요청은 막힌다")
	void unknownResultKeepsProcessing() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);

		confirm(user.session(), "unavailable_1", order.orderId(), order.amount())
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("PAYMENT006"));

		// 실패로 단정하지 않는다. 돈이 나갔을 수 있어서 보정 작업이 토스에 조회해 확정할 때까지 승인 중으로 둔다
		assertThat(paymentStatus(order.orderId())).isEqualTo("PROCESSING");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PENDING");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("HELD");
		confirm(user.session(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PAYMENT003"));
		assertThat(gateway.confirmedOrderIds()).hasSize(1);
	}

	// ---------------------------------------------------------------- 권한

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("같은 결제를 동시에 승인해도 토스는 한 번만 불리고 한 건만 성공한다 (나머지는 이미 처리 중 409)")
	void concurrentConfirmCallsGatewayOnce() throws Exception {
		Show show = show(2);
		User user = signup("first");
		User warm = signup("warm");
		Order order = prepareOrder(user, show, 0, 1);
		Order warmOrder = prepareOrder(warm, show, 1, 1);
		// 첫 요청은 클래스 로딩 때문에 느려서 락 대기 시간(1초)을 넘기지 않도록 다른 결제로 한 번 미리 실행해 둔다
		confirm(warm.session(), "pay_key_warm", warmOrder.orderId(), warmOrder.amount()).andExpect(status().isOk());
		gateway.reset();

		// slow_ 는 성공하지만 느리게 응답해서, 첫 요청이 토스를 기다리는 동안 나머지 요청이 겹친다
		List<String> results = runConcurrently(5, i -> () -> {
			MvcResult result = confirm(user.session(), "slow_pay_key", order.orderId(), order.amount()).andReturn();
			return result.getResponse().getStatus() == 200 ? "OK" : read(result, "$.code");
		});

		assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
		assertThat(results.stream().filter(r -> !r.equals("OK"))).allMatch(r -> r.equals("PAYMENT003") || r.equals("COMMON004"));
		assertThat(gateway.confirmedOrderIds()).containsExactly(order.orderId());
		assertThat(paymentStatus(order.orderId())).isEqualTo("DONE");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
	}
}
