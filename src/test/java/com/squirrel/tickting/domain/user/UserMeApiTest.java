package com.squirrel.tickting.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 내 정보 조회. 회원은 실제 가입 API 로 만들고, 가입 응답의 액세스 토큰으로 호출한다. */
class UserMeApiTest extends IntegrationTestSupport {

	private static final String SIGNUP = "/api/v1/auth/users/signup";
	private static final String URL = "/api/v1/users/me";

	/** 가입하고 "Bearer {액세스 토큰}" 을 돌려준다. */
	private String signupAndGetBearer(String email, String ci) throws Exception {
		MvcResult result = postJson(SIGNUP, userSignupBody(email, ci))
			.andExpect(status().isCreated())
			.andReturn();
		return "Bearer " + accessToken(result);
	}

	private String bearer(Long id, AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(id, type, role, businessId));
	}

	@Test
	@DisplayName("로그인한 예매자는 자기 정보를 조회하고, 비밀번호와 본인인증 값은 응답에 없다")
	void getMyInfo() throws Exception {
		String token = signupAndGetBearer("User@Example.com", "ci-001");
		Long userId = jdbc.queryForObject("SELECT id FROM user_member", Long.class);

		MvcResult result = mockMvc.perform(get(URL).header("Authorization", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.userId").value(userId))
			.andExpect(jsonPath("$.data.email").value("user@example.com"))
			.andExpect(jsonPath("$.data.name").value("홍길동"))
			.andExpect(jsonPath("$.data.nickname").value("tester"))
			.andExpect(jsonPath("$.data.phone").value("01012345678"))
			.andExpect(jsonPath("$.data.profileImg").isEmpty())
			.andExpect(jsonPath("$.data.birthDate").value("1990-01-01"))
			.andExpect(jsonPath("$.data.createdAt").isNotEmpty())
			.andReturn();

		assertThat(result.getResponse().getContentAsString())
			.doesNotContain("password", "ciHash", "ci_hash", "ci-001");
	}

	@Test
	@DisplayName("두 회원이 각자 조회하면 각자 자기 정보만 받는다")
	void eachUserSeesOnlyOwnInfo() throws Exception {
		String first = signupAndGetBearer("first@example.com", "ci-001");
		String second = signupAndGetBearer("second@example.com", "ci-002");

		mockMvc.perform(get(URL).header("Authorization", first))
			.andExpect(jsonPath("$.data.email").value("first@example.com"));
		mockMvc.perform(get(URL).header("Authorization", second))
			.andExpect(jsonPath("$.data.email").value("second@example.com"));
	}

	@Test
	@DisplayName("토큰 없이 호출하면 401 AUTH200 이다")
	void needsLogin() throws Exception {
		mockMvc.perform(get(URL))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("판매자·관리자 토큰으로 호출하면 403 AUTH100 이다 (번호가 같은 예매자가 있어도 보이지 않는다)")
	void sellerAndAdminAreForbidden() throws Exception {
		signupAndGetBearer("user@example.com", "ci-001");

		mockMvc.perform(get(URL).header("Authorization", bearer(1L, AccountType.SELLER, "OWNER", 1L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
		mockMvc.perform(get(URL).header("Authorization", bearer(1L, AccountType.ADMIN, null, null)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("탈퇴한 회원의 토큰이 아직 살아 있어도 404 USER002 이다")
	void withdrawnUserIsNotFound() throws Exception {
		String token = signupAndGetBearer("user@example.com", "ci-001");
		jdbc.update("UPDATE user_member SET deleted_at = NOW()");

		mockMvc.perform(get(URL).header("Authorization", token))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER002"));
	}

	@Test
	@DisplayName("정지된 회원도 토큰이 살아 있는 동안은 자기 정보를 볼 수 있다")
	void suspendedUserCanStillRead() throws Exception {
		String token = signupAndGetBearer("user@example.com", "ci-001");
		jdbc.update("UPDATE user_member SET status = 'SUSPENDED'");

		mockMvc.perform(get(URL).header("Authorization", token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value("user@example.com"));
	}
}
