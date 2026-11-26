package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 좌석 점유 요청 횟수 제한. 실제 Redis 로 API 를 호출한다.
 * 횟수는 3번으로 줄이고, 테스트 도중에 횟수가 다시 시작하지 않게 세는 시간은 길게 둔다.
 */
class HoldLimitApiTest extends BookingTestSupport {

	private static final int MAX_REQUESTS = 3;
	private static final long WINDOW_SECONDS = 60;

	@DynamicPropertySource
	static void holdLimitOn(DynamicPropertyRegistry registry) {
		registry.add("app.booking.hold-limit.enabled", () -> "true");
		registry.add("app.booking.hold-limit.max-requests", () -> String.valueOf(MAX_REQUESTS));
		registry.add("app.booking.hold-limit.window", () -> WINDOW_SECONDS + "s");
	}

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void clearHoldLimitKeys() {
		Set<String> keys = redis.keys("tickting:hold-limit:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@Test
	@DisplayName("실패한 시도까지 세어 정한 횟수를 넘으면 429 BOOKING011 이고 좌석은 잡히지 않는다. 다른 계정은 영향이 없고 시간이 지나면 풀린다")
	void blocksAccountAfterTooManyRequests() throws Exception {
		Show show = show(3);
		User macro = signup("macro");
		User other = signup("other");
		Long taken = show.seatIds().get(0);
		Long free = show.seatIds().get(1);
		book(other.session(), show.scheduleId(), List.of(taken)).andExpect(status().isCreated());

		for (int i = 0; i < MAX_REQUESTS; i++) {
			book(macro.session(), show.scheduleId(), List.of(taken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BOOKING000"));
		}
		book(macro.session(), show.scheduleId(), List.of(free))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("BOOKING011"));

		assertThat(seatStatus(free)).isEqualTo("AVAILABLE");
		book(other.session(), show.scheduleId(), List.of(show.seatIds().get(2))).andExpect(status().isCreated());
		// 만료가 없으면 한 번 막힌 계정이 영원히 점유하지 못한다. 막힌 뒤의 요청이 만료를 늘리지도 않는다
		String key = "tickting:hold-limit:USER:" + macro.id();
		assertThat(redis.getExpire(key)).isBetween(1L, WINDOW_SECONDS);
		redis.delete(key);
		book(macro.session(), show.scheduleId(), List.of(free)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("한 계정이 동시에 몰아 보내도 정한 횟수만 점유까지 간다")
	void concurrentRequestsCannotPassLimit() throws Exception {
		int requests = 10;
		Show show = show(requests);
		User macro = signup("macro");

		List<String> results = runConcurrently(requests,
			i -> () -> bookForResult(macro.session(), show.scheduleId(), List.of(show.seatIds().get(i))));

		assertThat(countOf("BOOKING011", results)).isEqualTo(requests - MAX_REQUESTS);
	}
}
