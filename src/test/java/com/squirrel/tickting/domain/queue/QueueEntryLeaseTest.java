package com.squirrel.tickting.domain.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.captcha.interceptor.CaptchaInterceptor;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 입장 유효 시간은 짧게 두고, 구역·좌석·점유 요청이 올 때마다 늘리되 입장한 지 상한(entry-max)이 지나면 늘리지 않는다.
 * 실제 PostgreSQL·Redis(Testcontainers)에서 API 를 호출하고, 만료 시각은 Redis 의 값을 직접 읽어 확인한다. (시간을 기다리지 않는다)
 * 유효 시간 10초, 상한 60초로 줄여 두고 만료 시각을 직접 바꿔서 "남은 시간이 절반 아래" 같은 상황을 만든다.
 */
class QueueEntryLeaseTest extends BookingTestSupport {

	private static final long TTL_MS = 10_000;

	@DynamicPropertySource
	static void queueOn(DynamicPropertyRegistry registry) {
		registry.add("app.queue.enabled", () -> "true");
		registry.add("app.queue.max-active", () -> "2");
		registry.add("app.queue.entry-ttl", () -> "10s");
		registry.add("app.queue.entry-max", () -> "60s");
	}

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void clearQueueKeys() {
		Set<String> keys = redis.keys("tickting:queue:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@Test
	@DisplayName("입장하면 입장 상한 표시가 생기고, 나가면 사라진다")
	void admissionCreatesCapMarkerAndLeaveRemovesIt() throws Exception {
		Show show = show(1);
		User user = signup("user");

		enter(user, show);

		Long remaining = redis.getExpire(admittedKey(show, user), TimeUnit.MILLISECONDS);
		assertThat(remaining).isPositive().isLessThanOrEqualTo(60_000L);
		leave(user, show).andExpect(status().isOk());
		assertThat(redis.hasKey(admittedKey(show, user))).isFalse();
	}

	@Test
	@DisplayName("남은 시간이 유효 시간의 절반 아래일 때 구역 요약을 요청하면 만료 시각이 늘어난다")
	void activityExtendsAdmissionWhenLessThanHalfRemains() throws Exception {
		Show show = show(1);
		User user = signup("user");
		enter(user, show);
		setExpiry(show, user, System.currentTimeMillis() + 2_000);

		zones(user, show).andExpect(status().isOk());

		assertThat(expiry(show, user)).isGreaterThan(System.currentTimeMillis() + TTL_MS - 1_500);
	}

	@Test
	@DisplayName("남은 시간이 절반 이상이면 요청해도 만료 시각을 바꾸지 않는다 (요청마다 쓰지 않는다)")
	void doesNotExtendWhenMoreThanHalfRemains() throws Exception {
		Show show = show(1);
		User user = signup("user");
		enter(user, show);
		long expiry = System.currentTimeMillis() + 8_000;
		setExpiry(show, user, expiry);

		zones(user, show).andExpect(status().isOk());

		assertThat(expiry(show, user)).isEqualTo(expiry);
	}

	@Test
	@DisplayName("입장 상한이 지났으면 요청해도 늘리지 않고, 상한이 곧 끝나면 그 시각까지만 늘린다")
	void respectsEntryMax() throws Exception {
		Show show = show(1);
		User capped = signup("capped");
		User nearCap = signup("nearcap");
		enter(capped, show);
		enter(nearCap, show);

		// 상한 표시가 사라졌다 = 입장한 지 상한이 지났다
		redis.delete(admittedKey(show, capped));
		long expiry = System.currentTimeMillis() + 2_000;
		setExpiry(show, capped, expiry);
		zones(capped, show).andExpect(status().isOk());
		assertThat(expiry(show, capped)).isEqualTo(expiry);

		// 상한이 3초 남았으면 유효 시간(10초)만큼이 아니라 3초 뒤까지만 늘린다
		redis.expire(admittedKey(show, nearCap), 3, TimeUnit.SECONDS);
		setExpiry(show, nearCap, System.currentTimeMillis() + 1_000);
		zones(nearCap, show).andExpect(status().isOk());
		long extended = expiry(show, nearCap);
		assertThat(extended).isGreaterThan(System.currentTimeMillis() + 1_500).isLessThanOrEqualTo(System.currentTimeMillis() + 3_200);
	}

	// ---------------------------------------------------------------- 도우미

	private String activeKey(Show show) {
		return "tickting:queue:{" + show.scheduleId() + "}:active";
	}

	private String admittedKey(Show show, User user) {
		return "tickting:queue:{" + show.scheduleId() + "}:admitted:USER:" + user.id();
	}

	private void setExpiry(Show show, User user, long epochMillis) {
		redis.opsForZSet().add(activeKey(show), "USER:" + user.id(), epochMillis);
	}

	private long expiry(Show show, User user) {
		return redis.opsForZSet().score(activeKey(show), "USER:" + user.id()).longValue();
	}

	private ResultActions enter(User user, Show show) throws Exception {
		return mockMvc.perform(post("/api/v1/schedules/" + show.scheduleId() + "/queue")
			.cookie(user.session())
			.header(CaptchaInterceptor.TOKEN_HEADER, "pass"));
	}

	private ResultActions leave(User user, Show show) throws Exception {
		return mockMvc.perform(delete("/api/v1/schedules/" + show.scheduleId() + "/queue").cookie(user.session()));
	}

	private ResultActions zones(User user, Show show) throws Exception {
		return mockMvc.perform(get("/api/v1/schedules/" + show.scheduleId() + "/zones").cookie(user.session()));
	}
}
