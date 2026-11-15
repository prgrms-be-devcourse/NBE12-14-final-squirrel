package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * v2(Redis 세션) 로그인. 세션이 실제로 Redis 에 저장되고 지워지는지 확인하고,
 * 로그인한 뒤에는 경로를 바꾸지 않은 업무 API(/users/me, /admin/**, /seller/**)를 쿠키만으로 쓸 수 있는지 본다.
 */
class SessionAuthApiTest extends IntegrationTestSupport {

	private static final String V2 = "/api/v2/auth";
	private static final String SESSION_KEYS = "tickting:session:*";

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	PasswordEncoder passwordEncoder;

	/** 앞선 테스트가 남긴 세션이 섞이지 않게 Redis 의 세션을 모두 지운다. */
	@BeforeEach
	void clearSessions() {
		Set<String> keys = redis.keys(SESSION_KEYS);
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private int sessionCount() {
		Set<String> keys = redis.keys(SESSION_KEYS);
		return keys == null ? 0 : (int) keys.stream().filter(key -> !key.contains(":expires:")).count();
	}

	/** 응답의 Set-Cookie 에서 SESSION 쿠키를 꺼낸다. 없으면 null */
	private Cookie sessionCookie(MvcResult result) {
		List<String> headers = result.getResponse().getHeaders("Set-Cookie");
		return headers.stream()
			.filter(header -> header.startsWith("SESSION="))
			.map(header -> header.substring("SESSION=".length(), header.indexOf(';') < 0 ? header.length() : header.indexOf(';')))
			.filter(value -> !value.isEmpty())
			.findFirst()
			.map(value -> new Cookie("SESSION", value))
			.orElse(null);
	}

	private String setCookieHeader(MvcResult result) {
		return result.getResponse().getHeaders("Set-Cookie").stream()
			.filter(header -> header.startsWith("SESSION="))
			.findFirst().orElse("");
	}

	private ResultActions callMe(Cookie session) throws Exception {
		var request = get("/api/v1/users/me");
		if (session != null) {
			request.cookie(session);
		}
		return mockMvc.perform(request);
	}

	private MvcResult userLogin(String email, String password) throws Exception {
		return postJson(V2 + "/users/login", loginBody(email, password)).andReturn();
	}

	private void insertAdmin(String email) {
		jdbc.update("""
			INSERT INTO admin_member (email, password, name, status, created_at, updated_at)
			VALUES (?, ?, '관리자', 'ACTIVE', now(), now())
			""", email, passwordEncoder.encode(PASSWORD));
	}

	// ---------------------------------------------------------------- 로그인

	@Test
	@DisplayName("로그인하면 SESSION 쿠키가 내려가고 세션이 Redis 에 저장되며, 토큰은 내려가지 않는다")
	void loginCreatesSession() throws Exception {
		postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated());
		clearSessions();

		MvcResult result = postJson(V2 + "/users/login", loginBody("user@example.com", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.accountType").value("USER"))
			.andExpect(jsonPath("$.data.accessToken").doesNotExist())
			.andReturn();

		assertThat(sessionCookie(result)).isNotNull();
		// 로컬 프로필은 http 라서 Secure 는 붙지 않는다
		assertThat(setCookieHeader(result)).contains("HttpOnly", "SameSite=Lax", "Path=/");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("accessToken", "refreshToken");
		assertThat(sessionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("가입하면 바로 로그인되어 SESSION 쿠키가 내려가고 세션이 Redis 에 저장되며, 토큰은 내려가지 않는다")
	void signupCreatesSession() throws Exception {
		MvcResult result = postJson(V2 + "/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.accountType").value("USER"))
			.andExpect(jsonPath("$.data.accessToken").doesNotExist())
			.andReturn();

		Cookie session = sessionCookie(result);
		assertThat(session).isNotNull();
		assertThat(setCookieHeader(result)).contains("HttpOnly", "SameSite=Lax");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("accessToken", "refreshToken");
		assertThat(sessionCount()).isEqualTo(1);
		// 가입과 함께 만들어진 세션으로 바로 내 정보를 볼 수 있다
		callMe(session).andExpect(status().isOk()).andExpect(jsonPath("$.data.email").value("user@example.com"));
	}

	@Test
	@DisplayName("가입이 실패하면(이메일 중복, 필수 약관 미동의) 세션이 만들어지지 않고 회원도 늘지 않는다")
	void failedSignupCreatesNoSession() throws Exception {
		postJson(V2 + "/users/signup", userSignupBody("user@example.com", "ci-001")).andExpect(status().isCreated());
		assertThat(sessionCount()).isEqualTo(1);

		postJson(V2 + "/users/signup", userSignupBody("user@example.com", "ci-002"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("USER000"));

		var withoutRequiredTerms = userSignupBody("other@example.com", "ci-003");
		withoutRequiredTerms.put("agreedTermIds", List.of(optionalTermId));
		postJson(V2 + "/users/signup", withoutRequiredTerms).andExpect(status().is4xxClientError());

		assertThat(sessionCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_member", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("로그인한 뒤 SESSION 쿠키만으로 경로를 바꾸지 않은 업무 API(내 정보)를 쓸 수 있고, 쿠키가 없으면 401 이다")
	void cookieAuthenticatesBusinessApi() throws Exception {
		postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated());

		Cookie session = sessionCookie(userLogin("user@example.com", PASSWORD));

		callMe(session)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value("user@example.com"));
		callMe(null)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("비밀번호가 틀리면 401 AUTH001 이고 세션도 쿠키도 만들어지지 않는다")
	void wrongPasswordCreatesNoSession() throws Exception {
		postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated());
		clearSessions();

		MvcResult result = postJson(V2 + "/users/login", loginBody("user@example.com", "wrong-password1!"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH001"))
			.andReturn();

		assertThat(sessionCookie(result)).isNull();
		assertThat(sessionCount()).isZero();
	}

	// ---------------------------------------------------------------- 역할

	@Test
	@DisplayName("관리자는 세션으로 관리자 API 를 쓰고, 예매자 세션은 관리자 API 에서 403 이다")
	void adminSessionAndUserSession() throws Exception {
		insertAdmin("admin@example.com");
		postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated());

		MvcResult adminLogin = postJson(V2 + "/admins/login", loginBody("admin@example.com", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accountType").value("ADMIN"))
			.andReturn();
		Cookie admin = sessionCookie(adminLogin);
		Cookie user = sessionCookie(userLogin("user@example.com", PASSWORD));

		mockMvc.perform(get("/api/v1/admin/seller-businesses").cookie(admin)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/admin/seller-businesses").cookie(user))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("판매자는 사업체가 승인된 뒤에만 세션 로그인되고, 세션으로 판매자 경로를 통과하며 예매자 세션은 403 이다")
	void sellerSession() throws Exception {
		postJson("/api/v1/auth/sellers/signup", sellerSignupBody("owner@company.com", "ci-owner", "1234567890"))
			.andExpect(status().isCreated());
		postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated());

		postJson(V2 + "/sellers/login", loginBody("owner@company.com", PASSWORD))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SELLER003"));

		jdbc.update("UPDATE seller_business SET status = 'APPROVED'");
		MvcResult login = postJson(V2 + "/sellers/login", loginBody("owner@company.com", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accountType").value("SELLER"))
			.andExpect(jsonPath("$.data.role").value("OWNER"))
			.andReturn();
		Cookie seller = sessionCookie(login);
		Cookie user = sessionCookie(userLogin("user@example.com", PASSWORD));

		// 아직 /seller/** API 가 없어서 404 면 보안 규칙을 통과한 것이다
		mockMvc.perform(get("/api/v1/seller/anything").cookie(seller)).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/seller/anything").cookie(user)).andExpect(status().isForbidden());
	}

	// ---------------------------------------------------------------- 로그아웃·세션 고정

	@Test
	@DisplayName("로그아웃하면 Redis 에서 세션이 지워져서 같은 쿠키로는 더 이상 로그인되지 않는다")
	void logoutDeletesSession() throws Exception {
		postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated());
		Cookie session = sessionCookie(userLogin("user@example.com", PASSWORD));
		assertThat(sessionCount()).isEqualTo(1);
		callMe(session).andExpect(status().isOk());

		mockMvc.perform(post(V2 + "/logout").cookie(session)).andExpect(status().isOk());

		assertThat(sessionCount()).isZero();
		callMe(session).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("로그인하지 않은 상태에서 로그아웃해도 200 이다")
	void logoutWithoutSession() throws Exception {
		mockMvc.perform(post(V2 + "/logout"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
	}

	@Test
	@DisplayName("이미 세션이 있는 상태로 다시 로그인하면 세션 번호가 바뀌고 이전 번호는 쓸 수 없다")
	void sessionIdChangesOnLogin() throws Exception {
		postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated());
		Cookie before = sessionCookie(userLogin("user@example.com", PASSWORD));

		MvcResult again = mockMvc.perform(post(V2 + "/users/login")
				.cookie(before)
				.contentType("application/json")
				.content(objectMapper.writeValueAsString(loginBody("user@example.com", PASSWORD))))
			.andExpect(status().isOk())
			.andReturn();
		Cookie after = sessionCookie(again);

		assertThat(after).isNotNull();
		assertThat(after.getValue()).isNotEqualTo(before.getValue());
		callMe(before).andExpect(status().isUnauthorized());
		callMe(after).andExpect(status().isOk());
	}
}
