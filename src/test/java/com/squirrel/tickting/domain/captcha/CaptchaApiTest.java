package com.squirrel.tickting.domain.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.captcha.interceptor.CaptchaInterceptor;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 대기열 줄 서기 앞의 캡챠 확인. 구글 대신 Mock 검증기(토큰이 있으면 통과, "fail" 이면 실패)로 실제 API 를 호출한다.
 * 캡챠를 통과하지 못한 요청이 줄에 서지 못하는 것(Redis 에 대기열 키가 생기지 않는 것)을 본다.
 */
class CaptchaApiTest extends BookingTestSupport {

	private static final String TOKEN_HEADER = CaptchaInterceptor.TOKEN_HEADER;

	@DynamicPropertySource
	static void queueOn(DynamicPropertyRegistry registry) {
		registry.add("app.queue.enabled", () -> "true");
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
	@DisplayName("캡챠 토큰이 없거나 실패하면 403 CAPTCHA000 이고 줄에 서지 않는다. 통과한 토큰이면 입장한다")
	void queueEntryRequiresCaptcha() throws Exception {
		Show show = show(1);
		User user = signupVerified("captcha");
		String path = "/api/v1/schedules/" + show.scheduleId() + "/queue";

		mockMvc.perform(post(path).header("Authorization", user.bearer()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CAPTCHA000"));
		mockMvc.perform(post(path).header("Authorization", user.bearer()).header(TOKEN_HEADER, "fail"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CAPTCHA000"));
		assertThat(redis.keys("tickting:queue:*")).isEmpty();

		mockMvc.perform(post(path).header("Authorization", user.bearer()).header(TOKEN_HEADER, "pass"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ADMITTED"));
	}

	@Test
	@DisplayName("상태 확인(GET)과 나가기(DELETE)는 캡챠 토큰 없이 된다. 화면이 2~3초마다 상태를 확인하기 때문이다")
	void statusAndLeaveDoNotRequireCaptcha() throws Exception {
		Show show = show(1);
		User user = signupVerified("polling");
		String path = "/api/v1/schedules/" + show.scheduleId() + "/queue";

		mockMvc.perform(post(path).header("Authorization", user.bearer()).header(TOKEN_HEADER, "pass"))
			.andExpect(status().isOk());

		mockMvc.perform(get(path).header("Authorization", user.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ADMITTED"));
		mockMvc.perform(delete(path).header("Authorization", user.bearer()))
			.andExpect(status().isOk());
	}
}
