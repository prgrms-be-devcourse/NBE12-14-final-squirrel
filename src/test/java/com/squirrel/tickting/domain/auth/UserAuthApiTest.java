package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class UserAuthApiTest extends IntegrationTestSupport {

	private static final String SIGNUP = "/api/v2/auth/users/signup";
	private static final String LOGIN = "/api/v2/auth/users/login";

	private MvcResult signup(String email) throws Exception {
		return postJson(SIGNUP, userSignupBody(email)).andExpect(status().isCreated()).andReturn();
	}

	private MvcResult login(String email, String password) throws Exception {
		return postJson(LOGIN, loginBody(email, password)).andReturn();
	}

	// ---------------------------------------------------------------- 가입

	@Test
	@DisplayName("가입하면 SESSION 쿠키로 바로 로그인되고, 비밀번호는 해시로만 저장된다")
	void signupStartsSessionAndStoresSafely() throws Exception {
		MvcResult result = postJson(SIGNUP, userSignupBody("User@Example.com"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.accountType").value("USER"))
			.andReturn();

		assertThat(sessionCookie(result)).isNotNull();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("accessToken", "refreshToken");

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
	@DisplayName("로그인하면 SESSION 쿠키를 받고, 이메일 대소문자·공백은 무시한다")
	void loginSuccess() throws Exception {
		signup("user@example.com");

		MvcResult result = postJson(LOGIN, loginBody(" USER@example.com ", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accountType").value("USER"))
			.andReturn();

		assertThat(sessionCookie(result)).isNotNull();
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
		assertThat(sessionCookie(wrongPassword)).isNull();
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
}
