package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.jwt.JwtProvider;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class UserAuthApiTest extends IntegrationTestSupport {

	private static final String SIGNUP = "/api/v1/auth/users/signup";
	private static final String LOGIN = "/api/v1/auth/users/login";
	private static final String REFRESH = "/api/v1/auth/refresh";

	private MvcResult signup(String email) throws Exception {
		return postJson(SIGNUP, userSignupBody(email)).andExpect(status().isCreated()).andReturn();
	}

	private MvcResult login(String email, String password) throws Exception {
		return postJson(LOGIN, loginBody(email, password)).andReturn();
	}

	// ---------------------------------------------------------------- 가입과 토큰

	@Test
	@DisplayName("가입하면 액세스 토큰은 본문, 리프레시 토큰은 HttpOnly 쿠키로 받고 DB 에는 해시만 저장된다")
	void signupIssuesTokensAndStoresSafely() throws Exception {
		MvcResult result = postJson(SIGNUP, userSignupBody("User@Example.com"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.data.accountType").value("USER"))
			.andExpect(jsonPath("$.data.expiresIn").value(900))
			.andExpect(jsonPath("$.data.accessToken").isNotEmpty())
			.andReturn();

		String setCookie = result.getResponse().getHeader("Set-Cookie");
		assertThat(setCookie).contains("refreshToken=", "HttpOnly", "Path=/api/v1/auth", "SameSite=Lax");

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_member");
		assertThat(row.get("email")).isEqualTo("user@example.com");
		assertThat((String) row.get("password")).startsWith("$2").isNotEqualTo(PASSWORD);
		assertThat(row.get("name")).isEqualTo("홍길동");
		assertThat(row.get("phone")).isEqualTo("01012345678");
		assertThat(row.get("status")).isEqualTo("ACTIVE");
		assertThat(count("user_terms_agreement")).isEqualTo(2);
	}

	// ---------------------------------------------------------------- 중복

	@Test
	@DisplayName("대소문자·앞뒤 공백만 다른 이메일은 같은 이메일로 보고 409 이다")
	void emailDuplicateIgnoresCaseAndSpaces() throws Exception {
		signup("user@example.com");

		postJson(SIGNUP, userSignupBody("  USER@Example.COM "))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("USER000"));

		assertThat(count("user_member")).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 약관

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("같은 이메일로 동시에 가입하면 한 명만 만들어지고 나머지는 500 이 아니라 409 이다")
	void concurrentSignupSameEmail() throws Exception {
		List<MvcResult> results = runConcurrently(8, i -> () ->
			postJson(SIGNUP, userSignupBody("same@example.com")).andReturn());

		assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201)).hasSize(1);
		assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 409)).hasSize(7);
		for (MvcResult result : results) {
			if (result.getResponse().getStatus() == 409) {
				assertThat(read(result, "$.code")).isEqualTo("USER000");
			}
		}
		assertThat(count("user_member")).isEqualTo(1);
		assertThat(count("user_terms_agreement")).isEqualTo(2);
	}

	// ---------------------------------------------------------------- 로그인

	@Test
	@DisplayName("로그인하면 토큰을 받고, 이메일 대소문자·공백은 무시한다")
	void loginSuccess() throws Exception {
		signup("user@example.com");

		MvcResult result = postJson(LOGIN, loginBody(" USER@example.com ", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accountType").value("USER"))
			.andExpect(jsonPath("$.data.accessToken").isNotEmpty())
			.andReturn();

		assertThat(refreshCookie(result)).isNotBlank();
		assertThat(jwtProvider.parseAccessToken(accessToken(result)).getAccountType()).isEqualTo(AccountType.USER);
	}

	@Test
	@DisplayName("비밀번호가 틀린 경우와 없는 이메일은 같은 401 AUTH001 이고 같은 메시지다")
	void loginFailureDoesNotRevealWhichIsWrong() throws Exception {
		signup("user@example.com");

		MvcResult wrongPassword = login("user@example.com", "wrong-password1!");
		MvcResult unknownEmail = login("nobody@example.com", PASSWORD);

		assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
		assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(wrongPassword, "$.code")).isEqualTo("AUTH001");
		assertThat(read(unknownEmail, "$.code")).isEqualTo("AUTH001");
		assertThat(read(wrongPassword, "$.message")).isEqualTo(read(unknownEmail, "$.message"));
		assertThat(refreshCookie(wrongPassword)).isNull();
		assertThat(wrongPassword.getResponse().getContentAsString()).doesNotContain("accessToken");
	}

	@Test
	@DisplayName("이용이 제한된 회원은 비밀번호가 맞아도 로그인할 수 없다")
	void suspendedUserCannotLogin() throws Exception {
		signup("user@example.com");
		jdbc.update("UPDATE user_member SET status = 'SUSPENDED'");

		MvcResult result = login("user@example.com", PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(result, "$.code")).isEqualTo("AUTH003");
	}

	@Test
	@DisplayName("탈퇴한 회원은 로그인할 수 없다")
	void withdrawnUserCannotLogin() throws Exception {
		signup("user@example.com");
		jdbc.update("UPDATE user_member SET deleted_at = now()");

		MvcResult result = login("user@example.com", PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(result, "$.code")).isEqualTo("AUTH001");
	}

	// ---------------------------------------------------------------- 재발급

	@Test
	@DisplayName("리프레시 쿠키로 액세스 토큰을 다시 받는다")
	void refreshSuccess() throws Exception {
		String refresh = refreshCookie(signup("user@example.com"));

		MvcResult result = mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accountType").value("USER"))
			.andReturn();

		assertThat(refreshCookie(result)).isNotBlank();
		assertThat(jwtProvider.parseAccessToken(accessToken(result)).getAccountType()).isEqualTo(AccountType.USER);
	}

	@Test
	@DisplayName("액세스 토큰이나 위조·만료된 토큰을 리프레시 쿠키로 보내면 거절한다")
	void refreshWithWrongTokens() throws Exception {
		MvcResult signup = signup("user@example.com");
		Long id = jwtProvider.parseAccessToken(accessToken(signup)).getId();

		// 액세스 토큰은 리프레시 자리에 쓸 수 없다
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", accessToken(signup))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));

		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", "forged")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));

		String expired = new JwtProvider(new JwtProperties(
			jwtProperties.secret(), jwtProperties.issuer(), -10, -10))
			.createRefreshToken(id, AccountType.USER);
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", expired)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH202"));
	}

	@Test
	@DisplayName("이용이 제한되거나 탈퇴한 회원은 이미 받은 리프레시 토큰으로도 재발급받을 수 없다")
	void refreshBlockedForDisabledAccounts() throws Exception {
		String refresh = refreshCookie(signup("user@example.com"));

		jdbc.update("UPDATE user_member SET status = 'SUSPENDED'");
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH003"));

		jdbc.update("UPDATE user_member SET status = 'ACTIVE', deleted_at = now()");
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));
	}
}
