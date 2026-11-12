package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.terms.type.TermsType;
import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.jwt.JwtProvider;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class UserAuthApiTest extends IntegrationTestSupport {

	private static final String SIGNUP = "/api/v1/auth/users/signup";
	private static final String LOGIN = "/api/v1/auth/users/login";
	private static final String REFRESH = "/api/v1/auth/refresh";

	private MvcResult signup(String email, String ci) throws Exception {
		return postJson(SIGNUP, userSignupBody(email, ci)).andExpect(status().isCreated()).andReturn();
	}

	private MvcResult login(String email, String password) throws Exception {
		return postJson(LOGIN, loginBody(email, password)).andReturn();
	}

	// ---------------------------------------------------------------- 가입과 토큰

	@Test
	@DisplayName("가입하면 액세스 토큰은 본문, 리프레시 토큰은 HttpOnly 쿠키로 받고 DB 에는 해시만 저장된다")
	void signupIssuesTokensAndStoresSafely() throws Exception {
		MvcResult result = postJson(SIGNUP, userSignupBody("User@Example.com", "ci-001"))
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
		assertThat((String) row.get("ci_hash")).hasSize(64).isNotEqualTo("ci-001");
		assertThat(row.get("name")).isEqualTo("홍길동");
		assertThat(row.get("phone")).isEqualTo("01012345678");
		assertThat(row.get("status")).isEqualTo("ACTIVE");
		assertThat(count("user_terms_agreement")).isEqualTo(2);
	}

	@Test
	@DisplayName("발급된 액세스 토큰은 가입한 회원의 예매자 토큰이다")
	void issuedAccessTokenBelongsToTheNewUser() throws Exception {
		MvcResult result = signup("user@example.com", "ci-001");

		CustomUserDetails parsed = jwtProvider.parseAccessToken(accessToken(result));

		assertThat(parsed.getAccountType()).isEqualTo(AccountType.USER);
		assertThat(parsed.getId()).isEqualTo(jdbc.queryForObject("SELECT id FROM user_member", Long.class));
		assertThat(parsed.getRole()).isNull();
		assertThat(parsed.getBusinessId()).isNull();
	}

	@Test
	@DisplayName("예매자 토큰으로 판매자·관리자 경로는 403 이다")
	void userTokenCannotEnterOtherAreas() throws Exception {
		String token = accessToken(signup("user@example.com", "ci-001"));

		mockMvc.perform(get("/api/v1/seller/anything").header("Authorization", "Bearer " + token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
		mockMvc.perform(get("/api/v1/admin/anything").header("Authorization", "Bearer " + token))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("리프레시 토큰을 액세스 토큰 자리에 넣어 API 를 호출할 수 없다")
	void refreshTokenCannotCallApi() throws Exception {
		String refresh = refreshCookie(signup("user@example.com", "ci-001"));

		// 로그인 전용 API 가 아직 없어서 임의의 경로를 쓴다. 토큰 검사는 필터에서 먼저 일어난다.
		mockMvc.perform(get("/api/v1/anything").header("Authorization", "Bearer " + refresh))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));
	}

	// ---------------------------------------------------------------- 중복

	@Test
	@DisplayName("대소문자·앞뒤 공백만 다른 이메일은 같은 이메일로 보고 409 이다")
	void emailDuplicateIgnoresCaseAndSpaces() throws Exception {
		signup("user@example.com", "ci-001");

		postJson(SIGNUP, userSignupBody("  USER@Example.COM ", "ci-002"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("USER000"));

		assertThat(count("user_member")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 본인인증 정보(CI)로 다른 이메일을 만들 수 없다 (1인 1계정)")
	void sameCiCannotRegisterTwice() throws Exception {
		signup("first@example.com", "ci-same");

		postJson(SIGNUP, userSignupBody("second@example.com", "ci-same"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("USER001"));

		assertThat(count("user_member")).isEqualTo(1);
	}

	@Test
	@DisplayName("탈퇴한 회원의 이메일과 본인인증 정보로 다시 가입할 수 있다")
	void canRejoinAfterWithdrawal() throws Exception {
		signup("user@example.com", "ci-001");
		jdbc.update("UPDATE user_member SET deleted_at = now()");

		postJson(SIGNUP, userSignupBody("user@example.com", "ci-001")).andExpect(status().isCreated());

		assertThat(count("user_member")).isEqualTo(2);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM user_member WHERE deleted_at IS NULL", Long.class)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 약관

	@Test
	@DisplayName("선택 약관은 동의하지 않아도 가입되고, 동의하면 함께 기록된다")
	void optionalTermsAreOptional() throws Exception {
		signup("a@example.com", "ci-a");
		assertThat(count("user_terms_agreement")).isEqualTo(2);

		Map<String, Object> body = userSignupBody("b@example.com", "ci-b");
		List<Long> all = new ArrayList<>(requiredTermIds);
		all.add(optionalTermId);
		body.put("agreedTermIds", all);
		postJson(SIGNUP, body).andExpect(status().isCreated());

		assertThat(count("user_terms_agreement")).isEqualTo(5);
	}

	@Test
	@DisplayName("필수 약관에 동의하지 않으면 400 이고 회원이 만들어지지 않는다")
	void missingRequiredTerms() throws Exception {
		Map<String, Object> body = userSignupBody("user@example.com", "ci-001");
		body.put("agreedTermIds", List.of(requiredTermIds.get(0)));

		postJson(SIGNUP, body)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TERMS000"));

		body.put("agreedTermIds", List.of());
		postJson(SIGNUP, body).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TERMS000"));

		assertThat(count("user_member")).isZero();
		assertThat(count("user_terms_agreement")).isZero();
	}

	@Test
	@DisplayName("존재하지 않는 약관 번호가 섞여 있으면 400 이다")
	void unknownTermsId() throws Exception {
		Map<String, Object> body = userSignupBody("user@example.com", "ci-001");
		List<Long> ids = new ArrayList<>(requiredTermIds);
		ids.add(9999L);
		body.put("agreedTermIds", ids);

		postJson(SIGNUP, body)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TERMS001"));
		assertThat(count("user_member")).isZero();
	}

	@Test
	@DisplayName("약관이 개정되면 옛 버전 동의는 거절하고, 최신 버전에 동의해야 가입된다")
	void outdatedTermsAreRejected() throws Exception {
		Long newServiceTerms = saveTerms(TermsType.SERVICE, "2.0", true);

		// 옛 서비스 약관(1.0)과 개인정보 약관에 동의
		postJson(SIGNUP, userSignupBody("old@example.com", "ci-old"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TERMS002"));

		// 최신 서비스 약관(2.0)과 개인정보 약관에 동의
		Map<String, Object> body = userSignupBody("new@example.com", "ci-new");
		body.put("agreedTermIds", List.of(newServiceTerms, requiredTermIds.get(1)));
		postJson(SIGNUP, body).andExpect(status().isCreated());
	}

	// ---------------------------------------------------------------- 입력 검증

	@Test
	@DisplayName("잘못된 본인인증 토큰은 400 AUTH300 이고 아무것도 저장되지 않는다")
	void invalidIdentityToken() throws Exception {
		for (String token : List.of("garbage", "mock|이름|ci", "mock||ci-001|01012345678|19900101",
			"mock|홍길동|ci-001|12345|19900101", "mock|홍길동|ci-001|01012345678|not-a-date")) {
			Map<String, Object> body = userSignupBody("user@example.com", "ci-001");
			body.put("identityToken", token);

			postJson(SIGNUP, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH300"));
		}
		assertThat(count("user_member")).isZero();
	}

	@Test
	@DisplayName("비밀번호·이메일·닉네임 형식이 틀리면 400 이다")
	void invalidFields() throws Exception {
		for (String badPassword : List.of("short1!", "onlyletters!!", "12345678!!", "NoSpecial123")) {
			Map<String, Object> body = userSignupBody("user@example.com", "ci-001");
			body.put("password", badPassword);
			postJson(SIGNUP, body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON000"));
		}

		Map<String, Object> badEmail = userSignupBody("not-an-email", "ci-001");
		postJson(SIGNUP, badEmail).andExpect(status().isBadRequest());

		Map<String, Object> badNickname = userSignupBody("user@example.com", "ci-001");
		badNickname.put("nickname", "a");
		postJson(SIGNUP, badNickname).andExpect(status().isBadRequest());

		assertThat(count("user_member")).isZero();
	}

	@Test
	@DisplayName("본문이 깨진 JSON 이거나 비어 있으면 500 이 아니라 400 이다")
	void brokenBody() throws Exception {
		mockMvc.perform(post(SIGNUP).contentType("application/json").content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
		mockMvc.perform(post(SIGNUP).contentType("application/json").content(""))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post(SIGNUP).contentType("application/json").content("{}"))
			.andExpect(status().isBadRequest());
	}

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("같은 이메일로 동시에 가입하면 한 명만 만들어지고 나머지는 500 이 아니라 409 이다")
	void concurrentSignupSameEmail() throws Exception {
		List<MvcResult> results = runConcurrently(8, i -> () ->
			postJson(SIGNUP, userSignupBody("same@example.com", "ci-race-" + i)).andReturn());

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

	@Test
	@DisplayName("같은 사람(CI)이 다른 이메일로 동시에 가입해도 한 계정만 만들어진다")
	void concurrentSignupSameCi() throws Exception {
		List<MvcResult> results = runConcurrently(8, i -> () ->
			postJson(SIGNUP, userSignupBody("user" + i + "@example.com", "ci-same-person")).andReturn());

		assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201)).hasSize(1);
		assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 409)).hasSize(7);
		assertThat(count("user_member")).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 로그인

	@Test
	@DisplayName("로그인하면 토큰을 받고, 이메일 대소문자·공백은 무시한다")
	void loginSuccess() throws Exception {
		signup("user@example.com", "ci-001");

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
		signup("user@example.com", "ci-001");

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
		signup("user@example.com", "ci-001");
		jdbc.update("UPDATE user_member SET status = 'SUSPENDED'");

		MvcResult result = login("user@example.com", PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(result, "$.code")).isEqualTo("AUTH003");
	}

	@Test
	@DisplayName("탈퇴한 회원은 로그인할 수 없다")
	void withdrawnUserCannotLogin() throws Exception {
		signup("user@example.com", "ci-001");
		jdbc.update("UPDATE user_member SET deleted_at = now()");

		MvcResult result = login("user@example.com", PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(result, "$.code")).isEqualTo("AUTH001");
	}

	@Test
	@DisplayName("예매자 계정으로 판매자 로그인 주소를 쓸 수 없다 (계정 테이블이 따로다)")
	void userCannotLoginAsSeller() throws Exception {
		signup("user@example.com", "ci-001");

		postJson("/api/v1/auth/sellers/login", loginBody("user@example.com", PASSWORD))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH001"));
	}

	// ---------------------------------------------------------------- 재발급

	@Test
	@DisplayName("리프레시 쿠키로 액세스 토큰을 다시 받는다")
	void refreshSuccess() throws Exception {
		String refresh = refreshCookie(signup("user@example.com", "ci-001"));

		MvcResult result = mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accountType").value("USER"))
			.andReturn();

		assertThat(refreshCookie(result)).isNotBlank();
		assertThat(jwtProvider.parseAccessToken(accessToken(result)).getAccountType()).isEqualTo(AccountType.USER);
	}

	@Test
	@DisplayName("리프레시 쿠키가 없으면 401 AUTH200 이다")
	void refreshWithoutCookie() throws Exception {
		mockMvc.perform(post(REFRESH))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("액세스 토큰이나 위조·만료된 토큰을 리프레시 쿠키로 보내면 거절한다")
	void refreshWithWrongTokens() throws Exception {
		MvcResult signup = signup("user@example.com", "ci-001");
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
		String refresh = refreshCookie(signup("user@example.com", "ci-001"));

		jdbc.update("UPDATE user_member SET status = 'SUSPENDED'");
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH003"));

		jdbc.update("UPDATE user_member SET status = 'ACTIVE', deleted_at = now()");
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));
	}

	@Test
	@DisplayName("예매자 토큰에 관리자 계정 종류를 끼워 넣은 리프레시 토큰은 거절한다 (관리자 로그인은 아직 없다)")
	void adminRefreshTokenIsNotAccepted() throws Exception {
		String adminRefresh = jwtProvider.createRefreshToken(1L, AccountType.ADMIN);

		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", adminRefresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));
	}
}
