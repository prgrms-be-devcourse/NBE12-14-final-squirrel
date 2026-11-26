package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

/** 관리자는 가입이 없어서 테스트가 계정을 직접 넣는다. (시드 계정의 비밀번호는 테스트에 적지 않는다) */
class AdminAuthApiTest extends IntegrationTestSupport {

	private static final String LOGIN = "/api/v2/auth/admins/login";
	private static final String EMAIL = "admin@example.com";

	@Autowired
	PasswordEncoder passwordEncoder;

	private void insertAdmin(String email, String status) {
		jdbc.update("""
			INSERT INTO admin_member (email, password, name, status, created_at, updated_at)
			VALUES (?, ?, '관리자', ?, now(), now())
			""", email, passwordEncoder.encode(PASSWORD), status);
	}

	private MvcResult login(String email, String password) throws Exception {
		return postJson(LOGIN, loginBody(email, password)).andReturn();
	}

	@Test
	@DisplayName("관리자는 이메일·비밀번호로 로그인하고, 이메일 대소문자·공백은 무시한다")
	void loginSuccess() throws Exception {
		insertAdmin(EMAIL, "ACTIVE");

		MvcResult result = postJson(LOGIN, loginBody(" Admin@Example.com ", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.accountType").value("ADMIN"))
			.andExpect(jsonPath("$.data.role").doesNotExist())
			.andReturn();

		assertThat(result.getResponse().getHeader("Set-Cookie")).contains("SESSION=", "HttpOnly");
	}

	@Test
	@DisplayName("비밀번호가 틀린 경우와 없는 이메일은 같은 401 AUTH001 이고 같은 메시지다")
	void loginFailureDoesNotRevealWhichIsWrong() throws Exception {
		insertAdmin(EMAIL, "ACTIVE");

		MvcResult wrongPassword = login(EMAIL, "wrong-password1!");
		MvcResult unknownEmail = login("nobody@example.com", PASSWORD);

		assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
		assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(wrongPassword, "$.code")).isEqualTo("AUTH001");
		assertThat(read(unknownEmail, "$.code")).isEqualTo("AUTH001");
		assertThat(read(wrongPassword, "$.message")).isEqualTo(read(unknownEmail, "$.message"));
		assertThat(sessionCookie(wrongPassword)).isNull();
		assertThat(wrongPassword.getResponse().getContentAsString()).doesNotContain("accessToken");
	}

	@Test
	@DisplayName("사용 중지된 관리자는 비밀번호가 맞아도 401 AUTH003 이다")
	void inactiveAdminCannotLogin() throws Exception {
		insertAdmin(EMAIL, "INACTIVE");

		MvcResult result = login(EMAIL, PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(result, "$.code")).isEqualTo("AUTH003");
	}
}
