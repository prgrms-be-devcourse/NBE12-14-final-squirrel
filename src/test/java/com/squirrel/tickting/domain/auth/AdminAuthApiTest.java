package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

/** 관리자는 가입이 없어서 테스트가 계정을 직접 넣는다. (시드 계정의 비밀번호는 테스트에 적지 않는다) */
class AdminAuthApiTest extends IntegrationTestSupport {

	private static final String LOGIN = "/api/v1/auth/admins/login";
	private static final String REFRESH = "/api/v1/auth/refresh";
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
			.andExpect(jsonPath("$.data.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.data.accountType").value("ADMIN"))
			.andExpect(jsonPath("$.data.role").doesNotExist())
			.andExpect(jsonPath("$.data.expiresIn").value(900))
			.andReturn();

		assertThat(result.getResponse().getHeader("Set-Cookie")).contains("refreshToken=", "HttpOnly");
		CustomUserDetails parsed = jwtProvider.parseAccessToken(accessToken(result));
		assertThat(parsed.getAccountType()).isEqualTo(AccountType.ADMIN);
		assertThat(parsed.getId()).isEqualTo(jdbc.queryForObject("SELECT id FROM admin_member", Long.class));
		assertThat(parsed.getBusinessId()).isNull();
	}

	@Test
	@DisplayName("관리자 토큰으로 관리자 경로를 통과하고(404 는 경로가 아직 없어서), 판매자 경로는 403 이다")
	void adminTokenOpensAdminPathsOnly() throws Exception {
		insertAdmin(EMAIL, "ACTIVE");
		String token = accessToken(login(EMAIL, PASSWORD));

		mockMvc.perform(get("/api/v1/admin/anything").header("Authorization", "Bearer " + token))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/seller/anything").header("Authorization", "Bearer " + token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
		mockMvc.perform(get("/api/v1/bookings").header("Authorization", "Bearer " + token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
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
		assertThat(refreshCookie(wrongPassword)).isNull();
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

	@Test
	@DisplayName("삭제된 관리자는 로그인할 수 없다")
	void deletedAdminCannotLogin() throws Exception {
		insertAdmin(EMAIL, "ACTIVE");
		jdbc.update("UPDATE admin_member SET deleted_at = now()");

		MvcResult result = login(EMAIL, PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(result, "$.code")).isEqualTo("AUTH001");
	}

	@Test
	@DisplayName("예매자·판매자 계정으로는 관리자 로그인을 할 수 없다 (계정 테이블이 따로다)")
	void userAndSellerCannotLoginAsAdmin() throws Exception {
		postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-001"))
			.andExpect(status().isCreated());

		postJson(LOGIN, loginBody("user@example.com", PASSWORD))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH001"));
	}

	@Test
	@DisplayName("이메일 형식이 틀리거나 비밀번호가 비어 있으면 400 COMMON000 이다")
	void invalidRequestBody() throws Exception {
		postJson(LOGIN, Map.of("email", "not-an-email", "password", PASSWORD))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
		postJson(LOGIN, Map.of("email", EMAIL, "password", ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}

	@Test
	@DisplayName("관리자 리프레시 쿠키로 액세스 토큰을 다시 받는다")
	void refreshSuccess() throws Exception {
		insertAdmin(EMAIL, "ACTIVE");
		String refresh = refreshCookie(login(EMAIL, PASSWORD));

		MvcResult result = mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accountType").value("ADMIN"))
			.andReturn();

		assertThat(refreshCookie(result)).isNotBlank();
		assertThat(jwtProvider.parseAccessToken(accessToken(result)).getAccountType()).isEqualTo(AccountType.ADMIN);
	}

	@Test
	@DisplayName("로그인한 뒤 사용 중지되면 재발급은 401 AUTH003, 삭제되면 401 AUTH201 이다 (토큰이 아니라 DB 상태를 다시 읽는다)")
	void refreshChecksCurrentStatus() throws Exception {
		insertAdmin(EMAIL, "ACTIVE");
		String refresh = refreshCookie(login(EMAIL, PASSWORD));

		jdbc.update("UPDATE admin_member SET status = 'INACTIVE'");
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH003"));

		jdbc.update("UPDATE admin_member SET status = 'ACTIVE', deleted_at = now()");
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));
	}
}
