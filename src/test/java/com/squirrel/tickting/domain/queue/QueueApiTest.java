package com.squirrel.tickting.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.captcha.interceptor.CaptchaInterceptor;
import com.squirrel.tickting.domain.queue.service.QueueService;
import java.util.ArrayList;
import java.util.HashSet;
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
 * 회차 대기열. 실제 PostgreSQL·Redis(Testcontainers)에서 API 를 호출한다.
 * 정원은 2명으로 줄여 두고(app.queue.max-active), 정원·순서·입장 확인이 서버 여러 대의 동시 요청에서도 지켜지는지를 본다.
 */
class QueueApiTest extends BookingTestSupport {

	private static final int MAX_ACTIVE = 2;
	private static final long LEAVE_GRACE_MILLIS = 30_000;
	/** 컨테이너의 Redis 시계와 테스트 PC 시계의 차이를 허용하는 폭 */
	private static final long CLOCK_SKEW_MILLIS = 5_000;

	@DynamicPropertySource
	static void queueOn(DynamicPropertyRegistry registry) {
		registry.add("app.queue.enabled", () -> "true");
		registry.add("app.queue.max-active", () -> String.valueOf(MAX_ACTIVE));
	}

	@Autowired
	StringRedisTemplate redis;

	@MockitoSpyBean
	QueueService queueService;

	@AfterEach
	void cleanUpPayments() {
		jdbc.execute("TRUNCATE TABLE payment, refund RESTART IDENTITY");
	}

	@BeforeEach
	void clearQueueKeys() {
		Set<String> keys = redis.keys("tickting:queue:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	// ---------------------------------------------------------------- 정원·순서

	@Test
	@DisplayName("정원 안이면 바로 입장하고, 정원을 넘으면 순번을 받아 기다린다. 이미 줄에 있는 사람이 다시 들어와도 순번이 그대로다")
	void admitsUpToCapacityThenQueues() throws Exception {
		Show show = show(2);
		User first = signup("first");
		User second = signup("second");
		User third = signup("third");
		User fourth = signup("fourth");

		enter(first, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		enter(second, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		enter(third, show)
			.andExpect(jsonPath("$.data.status").value("WAITING"))
			.andExpect(jsonPath("$.data.position").value(1))
			.andExpect(jsonPath("$.data.waitingCount").value(1));
		enter(fourth, show).andExpect(jsonPath("$.data.position").value(2));

		// 다시 들어와도 순번·입장 상태가 바뀌지 않는다
		enter(third, show).andExpect(jsonPath("$.data.position").value(1));
		enter(first, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		queueStatus(fourth, show).andExpect(jsonPath("$.data.position").value(2));
	}

	@Test
	@DisplayName("입장한 사람이 나가면 빈 자리만큼 앞사람부터 입장하고, 뒷사람의 순번이 당겨진다")
	void nextInLineIsAdmittedWhenSomeoneLeaves() throws Exception {
		Show show = show(2);
		User first = signup("first");
		User second = signup("second");
		User third = signup("third");
		User fourth = signup("fourth");
		enter(first, show);
		enter(second, show);
		enter(third, show);
		enter(fourth, show);

		leave(first, show).andExpect(status().isOk());

		queueStatus(fourth, show).andExpect(jsonPath("$.data.status").value("WAITING"))
			.andExpect(jsonPath("$.data.position").value(1));
		queueStatus(third, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		queueStatus(first, show).andExpect(jsonPath("$.data.status").value("NONE"));
	}

	@Test
	@DisplayName("오래 상태를 확인하지 않아 떠난 것으로 보는 대기자는 자리를 주지 않고 건너뛴다")
	void skipsWaitingUserWhoStoppedPolling() throws Exception {
		Show show = show(2);
		User first = signup("first");
		User second = signup("second");
		User ghost = signup("ghost");
		User waiting = signup("waiting");
		enter(first, show);
		enter(second, show);
		enter(ghost, show);
		enter(waiting, show);
		// 유령이 마지막으로 확인한 지 waiting-timeout 이 지난 상태를 만든다. (확인 표시가 사라진다)
		redis.delete("tickting:queue:{" + show.scheduleId() + "}:seen:USER:" + ghost.id());

		leave(first, show);

		queueStatus(waiting, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		queueStatus(ghost, show).andExpect(jsonPath("$.data.status").value("NONE"));
	}

	@Test
	@DisplayName("동시에 몰려도 정원만큼만 입장하고, 나머지는 순번이 겹치지 않게 1번부터 차례로 받는다")
	void concurrentEntriesKeepCapacityAndUniquePositions() throws Exception {
		Show show = show(2);
		int people = 12;
		List<User> users = new ArrayList<>();
		for (int i = 0; i < people; i++) {
			users.add(signup("crowd" + i));
		}

		List<String> results = runConcurrently(people, i -> () -> {
			MvcResult result = enter(users.get(i), show).andReturn();
			String state = read(result, "$.data.status");
			return "ADMITTED".equals(state) ? "ADMITTED" : "WAITING:" + read(result, "$.data.position");
		});

		assertThat(countOf("ADMITTED", results)).isEqualTo(MAX_ACTIVE);
		Set<String> positions = new HashSet<>(results.stream().filter(r -> r.startsWith("WAITING")).toList());
		assertThat(positions).hasSize(people - MAX_ACTIVE);
		for (int position = 1; position <= people - MAX_ACTIVE; position++) {
			assertThat(positions).contains("WAITING:" + position);
		}
		assertThat(redis.opsForZSet().zCard("tickting:queue:{" + show.scheduleId() + "}:active"))
			.isEqualTo((long) MAX_ACTIVE);
	}

	// ---------------------------------------------------------------- 입장 확인

	@Test
	@DisplayName("입장하지 않은 사람은 구역 요약·구역 좌석 조회와 좌석 점유가 403 QUEUE000 이고, 입장하면 모두 통과한다")
	void onlyAdmittedUserCanViewZonesAndBook() throws Exception {
		Show show = show(2);
		// 구역 좌석 조회는 판매 좌석을 만든 회차만 볼 수 있다
		jdbc.update("UPDATE performance_schedule SET seats_generated_at = ? WHERE id = ?", now, show.scheduleId());
		User admitted = signup("admitted");
		User outsider = signup("outsider");
		enter(admitted, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));

		for (String path : List.of("/zones", "/zones/" + zoneId + "/seats")) {
			mockMvc.perform(get("/api/v1/schedules/" + show.scheduleId() + path).cookie(outsider.session()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("QUEUE000"));
			mockMvc.perform(get("/api/v1/schedules/" + show.scheduleId() + path).cookie(admitted.session()))
				.andExpect(status().isOk());
		}
		book(outsider.session(), show.scheduleId(), List.of(show.seatIds().get(0)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("QUEUE000"));
		book(admitted.session(), show.scheduleId(), List.of(show.seatIds().get(0))).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("로그인하지 않으면 구역 조회가 401 이고, 입장은 다른 회차에서는 통하지 않는다")
	void requiresLoginAndIsPerSchedule() throws Exception {
		Show show = show(1);
		Show other = show(1);
		User user = signup("user");
		enter(user, show);

		mockMvc.perform(get("/api/v1/schedules/" + show.scheduleId() + "/zones")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/schedules/" + other.scheduleId() + "/zones").cookie(user.session()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("QUEUE000"));
	}

	@Test
	@DisplayName("입장 만료 시각이 지나면 다시 403 QUEUE000 이고, 다시 입장하면 통과한다")
	void admissionExpires() throws Exception {
		Show show = show(1);
		User user = signup("user");
		enter(user, show);
		String zonesUrl = "/api/v1/schedules/" + show.scheduleId() + "/zones";
		mockMvc.perform(get(zonesUrl).cookie(user.session())).andExpect(status().isOk());

		// 만료 시각을 과거로 돌린다
		redis.opsForZSet().add("tickting:queue:{" + show.scheduleId() + "}:active", "USER:" + user.id(),
			System.currentTimeMillis() - 1000);

		mockMvc.perform(get(zonesUrl).cookie(user.session()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("QUEUE000"));
		enter(user, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		mockMvc.perform(get(zonesUrl).cookie(user.session())).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- 들어갈 수 있는 회차

	@Test
	@DisplayName("판매 중이 아닌 회차는 400 BOOKING002, 없는 회차는 404 BOOKING004 이고 대기열 키가 만들어지지 않는다")
	void rejectsSchedulesThatCannotBeBooked() throws Exception {
		Show beforeOpen = show(1, "PUBLISHED", "ON_SALE", now.plusDays(10), now.plusDays(1), now.plusDays(30), "BOTH", 2000);
		User user = signup("user");

		enter(user, beforeOpen).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOOKING002"));
		mockMvc.perform(post("/api/v1/schedules/999999/queue").cookie(user.session())
				.header(CaptchaInterceptor.TOKEN_HEADER, "pass"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING004"));

		assertThat(redis.keys("tickting:queue:*")).isEmpty();
	}

	// ---------------------------------------------------------------- 좌석 화면을 떠날 때

	@Test
	@DisplayName("좌석 화면을 떠난다고 알리면 유예 시간 뒤에 비워지게 줄어들고, 그 전에 좌석을 보거나 다시 들어오면 원래 만료 시각이 되살아난다")
	void leavingSoonIsUndoneWhenUserComesBack() throws Exception {
		Show show = show(2);
		User first = signup("first");
		User second = signup("second");
		User third = signup("third");
		enter(first, show);
		enter(second, show);
		enter(third, show).andExpect(jsonPath("$.data.status").value("WAITING"));
		double original = activeExpiry(first, show);

		leaveSoon(first, show).andExpect(status().isOk());
		double shortened = activeExpiry(first, show);
		// 여러 번 알려 와도 원래 만료 시각을 잃지 않는다
		leaveSoon(first, show);

		assertThat(shortened).isLessThanOrEqualTo(System.currentTimeMillis() + LEAVE_GRACE_MILLIS + CLOCK_SKEW_MILLIS);
		assertThat(activeExpiry(first, show)).isEqualTo(shortened);
		queueStatus(third, show).andExpect(jsonPath("$.data.status").value("WAITING"));

		mockMvc.perform(get("/api/v1/schedules/" + show.scheduleId() + "/zones").cookie(first.session()))
			.andExpect(status().isOk());
		assertThat(activeExpiry(first, show)).isEqualTo(original);

		leaveSoon(first, show);
		enter(first, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		assertThat(activeExpiry(first, show)).isEqualTo(original);
	}

	@Test
	@DisplayName("떠난다고 알린 뒤 돌아오지 않으면 다음 사람이 입장하고, 대기 중인 사람의 순번은 바뀌지 않는다")
	void slotIsFreedWhenUserDoesNotComeBack() throws Exception {
		Show show = show(2);
		User first = signup("first");
		User second = signup("second");
		User third = signup("third");
		enter(first, show);
		enter(second, show);
		enter(third, show);
		leaveSoon(third, show);
		queueStatus(third, show).andExpect(jsonPath("$.data.position").value(1));

		leaveSoon(first, show);
		// 유예 시간이 지난 상태를 만든다
		redis.opsForZSet().add(activeKey(show), "USER:" + first.id(), System.currentTimeMillis() - 1000);

		queueStatus(third, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));
		queueStatus(first, show).andExpect(jsonPath("$.data.status").value("NONE"));
	}

	// ---------------------------------------------------------------- 결제 뒤 자리 반납

	@Test
	@DisplayName("1인 최대 매수를 결제로 채우면 입장 자리를 반납하고, 결제 대기 좌석은 세지 않아 더 살 수 있으면 자리를 지킨다")
	void releasesSlotOnlyWhenPaidSeatsReachLimit() throws Exception {
		Show show = show(MAX_PER_PERSON * 2);
		User full = signup("full");
		User partial = signup("partial");
		enter(full, show);
		enter(partial, show);
		// partial: 절반은 결제하고 절반은 결제 대기로 둔다. 합치면 최대 매수지만 대기 좌석은 만료되면 다시 살 수 있다
		Long paidBooking = hold(partial, show, 0, 2);
		hold(partial, show, 2, 2);
		pay(partial, paidBooking, "pay_queue_partial");

		queueStatus(partial, show).andExpect(jsonPath("$.data.status").value("ADMITTED"));

		pay(full, hold(full, show, MAX_PER_PERSON, MAX_PER_PERSON), "pay_queue_full");

		queueStatus(full, show).andExpect(jsonPath("$.data.status").value("NONE"));
	}

	@Test
	@DisplayName("결제 뒤 대기열 자리를 반납하지 못해도 결제는 성공이다")
	void paymentSucceedsEvenWhenSlotReleaseFails() throws Exception {
		Show show = show(MAX_PER_PERSON);
		User user = signup("user");
		enter(user, show);
		Long bookingId = hold(user, show, 0, MAX_PER_PERSON);
		doThrow(new IllegalStateException("Redis 오류")).when(queueService).leave(any(), any());

		pay(user, bookingId, "pay_queue_redis_down");

		assertThat(jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId))
			.isEqualTo("PAID");
		verify(queueService).leave(user.id(), show.scheduleId());
	}

	// ---------------------------------------------------------------- 호출 도우미

	private Long hold(User user, Show show, int fromIndex, int seatCount) throws Exception {
		MvcResult booked = book(user.session(), show.scheduleId(), show.seatIds().subList(fromIndex, fromIndex + seatCount))
			.andExpect(status().isCreated()).andReturn();
		return Long.parseLong(read(booked, "$.data.bookingId"));
	}

	private void pay(User user, Long bookingId, String paymentKey) throws Exception {
		MvcResult prepared = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/payments")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"))))
			.andExpect(status().isOk()).andReturn();
		Integer amount = jdbc.queryForObject("SELECT total_amount FROM booking WHERE id = ?", Integer.class, bookingId);
		mockMvc.perform(post("/api/v1/payments/confirm")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("paymentKey", paymentKey,
					"orderId", read(prepared, "$.data.orderId"), "amount", amount))))
			.andExpect(status().isOk());
	}

	private ResultActions leaveSoon(User user, Show show) throws Exception {
		return mockMvc.perform(delete("/api/v1/schedules/" + show.scheduleId() + "/queue")
			.param("delayed", "true")
			.cookie(user.session()));
	}

	private String activeKey(Show show) {
		return "tickting:queue:{" + show.scheduleId() + "}:active";
	}

	private double activeExpiry(User user, Show show) {
		return redis.opsForZSet().score(activeKey(show), "USER:" + user.id());
	}

	private ResultActions enter(User user, Show show) throws Exception {
		return mockMvc.perform(post("/api/v1/schedules/" + show.scheduleId() + "/queue")
			.cookie(user.session())
			.header(CaptchaInterceptor.TOKEN_HEADER, "pass"));
	}

	private ResultActions queueStatus(User user, Show show) throws Exception {
		return mockMvc.perform(get("/api/v1/schedules/" + show.scheduleId() + "/queue")
			.cookie(user.session()));
	}

	private ResultActions leave(User user, Show show) throws Exception {
		return mockMvc.perform(delete("/api/v1/schedules/" + show.scheduleId() + "/queue")
			.cookie(user.session()));
	}
}
