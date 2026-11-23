package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 로그인 실패 제한. 같은 IP 에서 같은 이메일로 5번 틀리면 5분 동안 비밀번호가 맞아도 429(AUTH004)다.
 * 무차별 대입을 막는 장치라 잠금이 풀리는 길(다른 버전 API, 동시 요청)이 없는지 본다.
 */
class LoginFailureLimitApiTest extends IntegrationTestSupport {

	private static final String EMAIL = "user@example.com";
	private static final String WRONG = "wrong-password1!";
	private static final String IP = "203.0.113.10";

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void signup() throws Exception {
		postJson("/api/v1/auth/users/signup", userSignupBody(EMAIL)).andExpect(status().isCreated());
	}

	private ResultActions login(String url, String password, String ip) throws Exception {
		return mockMvc.perform(post(url)
			.with(request -> {
				request.setRemoteAddr(ip);
				return request;
			})
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(loginBody(EMAIL, password))));
	}

	private ResultActions v2Login(String password, String ip) throws Exception {
		return login("/api/v2/auth/users/login", password, ip);
	}

	private void failTimes(int times, String ip) throws Exception {
		for (int i = 0; i < times; i++) {
			v2Login(WRONG, ip).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH001"));
		}
	}

	@Test
	@DisplayName("같은 IP 에서 5번 틀리면 6번째는 비밀번호가 맞아도 429(AUTH004)이고, 잠금은 5분 뒤 풀리도록 만료가 걸린다")
	void locksAfterFiveFailures() throws Exception {
		failTimes(5, IP);

		v2Login(PASSWORD, IP)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("AUTH004"));

		Set<String> keys = redis.keys("tickting:login-fail:USER:*");
		assertThat(keys).hasSize(1);
		Long ttl = redis.getExpire(keys.iterator().next());
		assertThat(ttl).isBetween(1L, 300L);
	}

	@Test
	@DisplayName("4번까지는 맞는 비밀번호로 로그인되고, 성공하면 실패 횟수가 지워져 다시 4번 틀려도 잠기지 않는다")
	void successResetsFailures() throws Exception {
		failTimes(4, IP);
		v2Login(PASSWORD, IP).andExpect(status().isOk());

		failTimes(4, IP);
		v2Login(PASSWORD, IP).andExpect(status().isOk());
	}

	@Test
	@DisplayName("잠금은 이메일과 IP 를 함께 본다. 다른 IP 에서는 같은 계정으로 로그인된다 (남이 틀려서 주인을 잠글 수 없다)")
	void otherIpIsNotLocked() throws Exception {
		failTimes(5, IP);

		v2Login(PASSWORD, "198.51.100.20").andExpect(status().isOk());
	}

	@Test
	@DisplayName("v2(세션)에서 잠기면 v1(JWT) 로그인도 막힌다. 다른 버전 API 로 제한을 피해 갈 수 없다")
	void v1IsAlsoLocked() throws Exception {
		failTimes(5, IP);

		login("/api/v1/auth/users/login", PASSWORD, IP)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("AUTH004"));
	}

	@Test
	@DisplayName("틀린 비밀번호를 동시에 10번 보내도 횟수가 빠지지 않아 잠긴다")
	void concurrentFailuresAreAllCounted() throws Exception {
		List<Integer> statuses = runConcurrently(10, i -> () -> v2Login(WRONG, IP).andReturn().getResponse().getStatus());

		// 앞선 실패가 잠금에 닿은 뒤 도착한 요청은 비교 없이 429 로 막힌다
		assertThat(statuses).allMatch(code -> code == 401 || code == 429);
		v2Login(PASSWORD, IP).andExpect(status().isTooManyRequests());
	}
}
