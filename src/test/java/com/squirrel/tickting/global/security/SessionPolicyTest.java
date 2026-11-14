package com.squirrel.tickting.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MvcResult;

/** 시큐리티가 세션을 함부로 만들지 않는지, v2 인증 경로가 열려 있는지 본다. (세션 로그인 API 는 다음 PR 에서 추가한다) */
class SessionPolicyTest extends IntegrationTestSupport {

	private static final String SESSION_KEYS = "tickting:session:*";

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void clearSessions() {
		Set<String> keys = redis.keys(SESSION_KEYS);
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private int sessionCount() {
		Set<String> keys = redis.keys(SESSION_KEYS);
		return keys == null ? 0 : keys.size();
	}

	private boolean hasSessionCookie(MvcResult result) {
		return result.getResponse().getHeaders("Set-Cookie").stream().anyMatch(header -> header.startsWith("SESSION="));
	}

	@Test
	@DisplayName("JWT 로 호출하거나 로그인 없이 호출해도 세션이 만들어지지 않고, v1 JWT 인증은 그대로 동작한다")
	void noSessionWithoutSessionLogin() throws Exception {
		MvcResult signup = postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated())
			.andReturn();

		MvcResult withJwt = mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken(signup)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value("user@example.com"))
			.andReturn();
		MvcResult noLogin = mockMvc.perform(get("/api/v1/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"))
			.andReturn();

		assertThat(hasSessionCookie(withJwt)).isFalse();
		assertThat(hasSessionCookie(noLogin)).isFalse();
		assertThat(sessionCount()).isZero();
	}

	@Test
	@DisplayName("/api/v2/auth/** 는 로그인 없이 접근할 수 있다 (없는 경로는 401 이 아니라 404 가 난다)")
	void v2AuthPathsAreOpen() throws Exception {
		mockMvc.perform(post("/api/v2/auth/not-exists")).andExpect(status().isNotFound());
		// 같은 경로를 v1 업무 API 쪽으로 바꾸면 로그인이 필요하다
		mockMvc.perform(post("/api/v1/not-exists")).andExpect(status().isUnauthorized());
	}
}
