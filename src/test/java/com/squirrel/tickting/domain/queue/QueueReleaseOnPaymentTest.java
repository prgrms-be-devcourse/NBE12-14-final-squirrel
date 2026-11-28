package com.squirrel.tickting.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.captcha.interceptor.CaptchaInterceptor;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import com.squirrel.tickting.domain.queue.service.QueueService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 결제를 마치면 대기열 입장 자리를 바로 돌려줘서 다음 사람이 들어오는지. 실제 PostgreSQL·Redis(Testcontainers)에서 API 를 호출한다.
 * 정원은 3명으로 줄여 두고(app.queue.max-active), 토스 대신 Mock 게이트웨이를 쓴다. (reject_ 접두사는 승인 거절)
 */
class QueueReleaseOnPaymentTest extends BookingTestSupport {

	private static final int MAX_ACTIVE = 3;

	@DynamicPropertySource
	static void queueOn(DynamicPropertyRegistry registry) {
		registry.add("app.queue.enabled", () -> "true");
		registry.add("app.queue.max-active", () -> String.valueOf(MAX_ACTIVE));
	}

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	MockPaymentGateway gateway;

	@MockitoSpyBean
	QueueService queueService;

	@BeforeEach
	void reset() {
		Set<String> keys = redis.keys("tickting:queue:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
		jdbc.execute("TRUNCATE TABLE payment RESTART IDENTITY");
		gateway.reset();
	}

	@AfterEach
	void cleanUpPayments() {
		jdbc.execute("TRUNCATE TABLE payment RESTART IDENTITY");
	}

	// ---------------------------------------------------------------- 반납과 다음 사람 입장

	@Test
	@DisplayName("입장한 사람이 결제를 마치면 자리를 돌려주고, 대기 1순위가 입장한다. 다른 입장자는 그대로다")
	void paidUserReleasesSeatAndNextInLineIsAdmitted() throws Exception {
		Show show = show(4);
		User first = signup("first");
		User second = signup("second");
		User third = signup("third");
		User waiting = signup("waiting");
		enter(first, show);
		enter(second, show);
		enter(third, show);
		enter(waiting, show).andExpect(jsonPath("$.data.status").value("WAITING"));

		pay(first, show, 0).andExpect(status().isOk());

		queueStatus(waiting, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		queueStatus(first, show).andExpect(jsonPath("$.data.status").value("NONE"));
		queueStatus(second, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		queueStatus(third, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
	}

	@Test
	@DisplayName("정원만큼 입장해 있고 대기자가 더 많을 때 여럿이 동시에 결제를 마쳐도, 빈 자리만큼만 새로 입장하고 정원을 넘지 않는다")
	void concurrentPaymentsAdmitExactlyAsManyAsFreed() throws Exception {
		Show show = show(MAX_ACTIVE);
		List<User> admitted = new ArrayList<>();
		for (int i = 0; i < MAX_ACTIVE; i++) {
			User user = signup("admitted" + i);
			enter(user, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
			admitted.add(user);
		}
		List<User> waiting = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			User user = signup("waiting" + i);
			enter(user, show).andExpect(jsonPath("$.data.status").value("WAITING"));
			waiting.add(user);
		}
		// 좌석을 하나씩 잡고 결제를 준비해 둔다. (결제 승인만 동시에 한다)
		List<Order> orders = new ArrayList<>();
		for (int i = 0; i < MAX_ACTIVE; i++) {
			orders.add(prepareOrder(admitted.get(i), show, i));
		}

		List<Integer> statuses = runConcurrently(MAX_ACTIVE, i -> () ->
			confirm(admitted.get(i), "pay_key_" + i, orders.get(i)).andReturn().getResponse().getStatus());
		assertThat(statuses).containsOnly(200);

		long admittedWaiters = 0;
		for (User user : waiting) {
			MvcResult result = queueStatus(user, show).andReturn();
			if ("ADMITTED".equals(read(result, "$.data.status"))) {
				admittedWaiters++;
			}
		}
		assertThat(admittedWaiters).isEqualTo(MAX_ACTIVE);
		assertThat(redis.opsForZSet().zCard(key(show, "active"))).isEqualTo((long) MAX_ACTIVE);
		for (User payer : admitted) {
			queueStatus(payer, show).andExpect(jsonPath("$.data.status").value("NONE"));
		}
	}

	// ---------------------------------------------------------------- 돌려주지 않는 경우

	@Test
	@DisplayName("토스가 승인을 거절하면 입장 자리를 돌려주지 않는다")
	void rejectedPaymentKeepsSeat() throws Exception {
		Show show = show(2);
		User user = signup("user");
		enter(user, show);
		Order order = prepareOrder(user, show, 0);

		confirm(user, "reject_key", order).andExpect(status().is4xxClientError());

		queueStatus(user, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
	}

	@Test
	@DisplayName("다른 회차의 입장 자리는 돌려주지 않는다")
	void releasesOnlyThatSchedulesSeat() throws Exception {
		Show show = show(2);
		Show other = show(2);
		User user = signup("user");
		enter(user, show);
		enter(user, other);

		pay(user, show, 0).andExpect(status().isOk());

		queueStatus(user, show).andExpect(jsonPath("$.data.status").value("NONE"));
		queueStatus(user, other).andExpect(jsonPath("$.data.status").value("ADMITTED"));
	}

	@Test
	@DisplayName("자리를 돌려주다 실패해도 결제는 성공으로 끝난다")
	void paymentSucceedsEvenWhenReleaseFails() throws Exception {
		Show show = show(2);
		User user = signup("user");
		enter(user, show);
		Order order = prepareOrder(user, show, 0);
		doThrow(new RuntimeException("redis down")).when(queueService).releaseOnPayment(any(), any());

		confirm(user, "pay_key_ok", order)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.bookingStatus").value("PAID"));

		assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, order.bookingId()))
			.isEqualTo("PAID");
	}

	// ---------------------------------------------------------------- 도우미

	private record Order(Long bookingId, String orderId, int amount) {
	}

	/** 입장한 사람이 좌석을 점유하고 결제를 준비한다. */
	private Order prepareOrder(User user, Show show, int seatIndex) throws Exception {
		MvcResult booked = book(user.session(), show.scheduleId(), List.of(show.seatIds().get(seatIndex)))
			.andExpect(status().isCreated())
			.andReturn();
		Long bookingId = Long.parseLong(read(booked, "$.data.bookingId"));
		MvcResult prepared = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/payments")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"))))
			.andExpect(status().isOk())
			.andReturn();
		return new Order(bookingId, read(prepared, "$.data.orderId"), Integer.parseInt(read(prepared, "$.data.amount")));
	}

	private ResultActions pay(User user, Show show, int seatIndex) throws Exception {
		return confirm(user, "pay_key_ok", prepareOrder(user, show, seatIndex));
	}

	private ResultActions confirm(User user, String paymentKey, Order order) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("paymentKey", paymentKey);
		body.put("orderId", order.orderId());
		body.put("amount", order.amount());
		return mockMvc.perform(post("/api/v1/payments/confirm")
			.cookie(user.session())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private ResultActions enter(User user, Show show) throws Exception {
		return mockMvc.perform(post("/api/v1/schedules/" + show.scheduleId() + "/queue")
			.cookie(user.session())
			.header(CaptchaInterceptor.TOKEN_HEADER, "pass"));
	}

	private ResultActions queueStatus(User user, Show show) throws Exception {
		return mockMvc.perform(get("/api/v1/schedules/" + show.scheduleId() + "/queue").cookie(user.session()));
	}

	private static String key(Show show, String name) {
		return "tickting:queue:{" + show.scheduleId() + "}:" + name;
	}
}
