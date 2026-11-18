package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.booking.facade.BookingExpiryFacade;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
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
		MvcResult booked = book(user.bearer(), show.scheduleId(), show.seatIds().subList(fromIndex, fromIndex + seatCount))
			.andExpect(status().isCreated())
			.andReturn();
		Long bookingId = Long.parseLong(read(booked, "$.data.bookingId"));
		MvcResult prepared = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/payments")
				.header("Authorization", user.bearer())
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

	private ResultActions confirm(String bearer, String paymentKey, String orderId, Object amount) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("paymentKey", paymentKey);
		body.put("orderId", orderId);
		body.put("amount", amount);
		return mockMvc.perform(post("/api/v1/payments/confirm")
			.header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String paymentStatus(String orderId) {
		return jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
	}

	private String bookingStatus(Long bookingId) {
		return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
	}

	private LocalDateTime bookingExpiresAt(Long bookingId) {
		return jdbc.queryForObject("SELECT expires_at FROM booking WHERE id = ?", LocalDateTime.class, bookingId);
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

		confirm(user.bearer(), "pay_key_ok", order.orderId(), order.amount())
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
	@DisplayName("점유 시간이 넉넉하면 늘리지 않고, 승인 시작 때 남은 시간이 연장 시간보다 짧으면 예매와 좌석을 같은 시각으로 늘린다")
	void extendsHoldOnlyWhenShort() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Order roomy = prepareOrder(user, show, 0, 1);
		Order tight = prepareOrder(user, show, 1, 1);
		LocalDateTime roomyBefore = bookingExpiresAt(roomy.bookingId());
		// 남은 시간이 30초뿐인 예매. 승인 결과를 모르는 상태(502)로 두어 연장된 값을 확인한다
		LocalDateTime soon = LocalDateTime.now().plusSeconds(30);
		jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", soon, tight.bookingId());
		jdbc.update("UPDATE schedule_seat SET held_until = ? WHERE id = ?", soon, show.seatIds().get(1));

		confirm(user.bearer(), "unavailable_1", roomy.orderId(), roomy.amount()).andExpect(status().isBadGateway());
		confirm(user.bearer(), "unavailable_2", tight.orderId(), tight.amount()).andExpect(status().isBadGateway());

		assertThat(bookingExpiresAt(roomy.bookingId())).isEqualTo(roomyBefore);
		LocalDateTime extended = bookingExpiresAt(tight.bookingId());
		assertThat(extended).isBetween(LocalDateTime.now().plusSeconds(100), LocalDateTime.now().plusSeconds(130));
		assertThat(seatHeldUntil(show.seatIds().get(1))).isEqualTo(extended);
	}

	@Test
	@DisplayName("점유 연장은 예매당 한 번만 한다. 이미 승인을 시도한 예매는 다시 시도해도 늘리지 않는다")
	void extendsHoldOnlyOncePerBooking() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order first = prepareOrder(user, show, 1);
		jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", LocalDateTime.now().plusSeconds(30), first.bookingId());

		// 첫 시도는 거절당하고(FAILED) 점유가 늘어난다
		confirm(user.bearer(), "reject_1", first.orderId(), first.amount()).andExpect(status().isBadRequest());
		assertThat(bookingExpiresAt(first.bookingId())).isAfter(LocalDateTime.now().plusSeconds(100));

		// 시간이 흘러 다시 30초만 남은 것으로 하고 두 번째 시도(-02)를 한다
		// DB 는 마이크로초까지만 저장해서, 읽어 온 값과 같은지 비교할 수 있게 초 단위로 맞춘다
		LocalDateTime soon = LocalDateTime.now().plusSeconds(30).withNano(0);
		jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", soon, first.bookingId());
		MvcResult second = mockMvc.perform(post("/api/v1/bookings/" + first.bookingId() + "/payments")
				.header("Authorization", user.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"))))
			.andExpect(status().isOk())
			.andReturn();
		confirm(user.bearer(), "reject_2", read(second, "$.data.orderId"), first.amount()).andExpect(status().isBadRequest());

		assertThat(bookingExpiresAt(first.bookingId())).isEqualTo(soon);
	}

	@Test
	@DisplayName("점유를 늘린 예매는 승인 결과를 모르는 동안 만료 정리가 돌아도 만료되지 않는다")
	void extendedBookingSurvivesCleanup() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);
		jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", LocalDateTime.now().plusSeconds(30), order.bookingId());
		jdbc.update("UPDATE schedule_seat SET held_until = ? WHERE id = ?", LocalDateTime.now().plusSeconds(30), show.seatIds().get(0));

		confirm(user.bearer(), "unavailable_1", order.orderId(), order.amount()).andExpect(status().isBadGateway());
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

		confirm(user.bearer(), "pay_key_ok", order.orderId(), order.amount() - 1)
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

		confirm(user.bearer(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING007"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
		assertThat(paymentStatus(order.orderId())).isEqualTo("READY");
	}

	@Test
	@DisplayName("이미 승인한 결제를 다시 승인하면 409 PAYMENT003 이고 토스는 한 번만 불린다")
	void rejectsAlreadyProcessed() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);
		confirm(user.bearer(), "pay_key_ok", order.orderId(), order.amount()).andExpect(status().isOk());

		confirm(user.bearer(), "pay_key_ok", order.orderId(), order.amount())
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

		confirm(other.bearer(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAYMENT002"));
		confirm(other.bearer(), "pay_key_ok", "BK-NO-SUCH-ORDER", 1000)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAYMENT002"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
		assertThat(paymentStatus(order.orderId())).isEqualTo("READY");
	}

	@Test
	@DisplayName("결제 키·주문 ID·금액이 비었거나 금액이 0 이하이면 400 COMMON000 이다")
	void rejectsInvalidBody() throws Exception {
		User user = signup("first");

		confirm(user.bearer(), " ", "BK-1", 1000).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		confirm(user.bearer(), "pay_key", " ", 1000).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		confirm(user.bearer(), "pay_key", "BK-1", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		confirm(user.bearer(), "pay_key", "BK-1", 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
	}

	// ---------------------------------------------------------------- 토스 결과

	@Test
	@DisplayName("토스가 거절하면 400 PAYMENT005 로 토스의 사유를 알리고, 결제는 FAILED, 예매와 좌석은 점유 상태 그대로이며 새로 준비해 다시 결제할 수 있다")
	void rejectedByGateway() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);

		confirm(user.bearer(), "reject_card", order.orderId(), order.amount())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT005"))
			.andExpect(jsonPath("$.message").value("카드사에서 결제를 거절했습니다."));

		Map<String, Object> failed = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order.orderId());
		assertThat(failed.get("status")).isEqualTo("FAILED");
		assertThat(failed.get("fail_reason")).isEqualTo("카드사에서 결제를 거절했습니다.");
		assertThat(failed.get("payment_key")).isNull();
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PENDING");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("HELD");

		// 다시 준비하면 새 주문 ID(-02)가 나오고, 그 결제는 승인된다
		MvcResult again = mockMvc.perform(post("/api/v1/bookings/" + order.bookingId() + "/payments")
				.header("Authorization", user.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.orderId").value("BK" + order.bookingNo() + "-02"))
			.andReturn();
		confirm(user.bearer(), "pay_key_ok", read(again, "$.data.orderId"), order.amount()).andExpect(status().isOk());
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
	}

	@Test
	@DisplayName("승인 여부를 알 수 없으면 502 PAYMENT006 이고, 결제는 승인 중(PROCESSING)으로 남아 같은 결제의 재요청은 막힌다")
	void unknownResultKeepsProcessing() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);

		confirm(user.bearer(), "unavailable_1", order.orderId(), order.amount())
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("PAYMENT006"));

		// 실패로 단정하지 않는다. 돈이 나갔을 수 있어서 보정 작업이 토스에 조회해 확정할 때까지 승인 중으로 둔다
		assertThat(paymentStatus(order.orderId())).isEqualTo("PROCESSING");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PENDING");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("HELD");
		confirm(user.bearer(), "pay_key_ok", order.orderId(), order.amount())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PAYMENT003"));
		assertThat(gateway.confirmedOrderIds()).hasSize(1);
	}

	// ---------------------------------------------------------------- 권한

	@Test
	@DisplayName("토큰 없이 호출하면 401, 판매자·관리자 토큰으로 호출하면 403 이다")
	void needsUserRole() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Order order = prepareOrder(user, show, 1);
		String seller = "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.SELLER, "OWNER", 1L));
		String admin = "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.ADMIN, null, null));

		mockMvc.perform(post("/api/v1/payments/confirm")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("paymentKey", "k", "orderId", order.orderId(), "amount", order.amount()))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
		confirm(seller, "k", order.orderId(), order.amount()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH100"));
		confirm(admin, "k", order.orderId(), order.amount()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH100"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
	}

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
		confirm(warm.bearer(), "pay_key_warm", warmOrder.orderId(), warmOrder.amount()).andExpect(status().isOk());
		gateway.reset();

		// slow_ 는 성공하지만 느리게 응답해서, 첫 요청이 토스를 기다리는 동안 나머지 요청이 겹친다
		List<String> results = runConcurrently(5, i -> () -> {
			MvcResult result = confirm(user.bearer(), "slow_pay_key", order.orderId(), order.amount()).andReturn();
			return result.getResponse().getStatus() == 200 ? "OK" : read(result, "$.code");
		});

		assertThat(results.stream().filter("OK"::equals).count()).isEqualTo(1);
		assertThat(results.stream().filter(r -> !r.equals("OK"))).allMatch(r -> r.equals("PAYMENT003") || r.equals("COMMON004"));
		assertThat(gateway.confirmedOrderIds()).containsExactly(order.orderId());
		assertThat(paymentStatus(order.orderId())).isEqualTo("DONE");
		assertThat(bookingStatus(order.bookingId())).isEqualTo("PAID");
	}
}
