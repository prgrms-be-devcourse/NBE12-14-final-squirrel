package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 로그인한 상태의 비밀번호 변경(AUTH-29·30). 실제 PostgreSQL·Redis(Testcontainers)에서 가입·로그인 API 로 만든 세션으로 호출한다.
 * 요청의 IP 는 로그인과 변경이 같은 MockMvc 기본값이라, 실패 횟수가 같은 키에 쌓인다.
 */
class PasswordChangeApiTest extends IntegrationTestSupport {

	private static final String USER_URL = "/api/v1/users/me/password";
	private static final String SELLER_URL = "/api/v1/seller/me/password";
	private static final String USER_LOGIN = "/api/v2/auth/users/login";
	private static final String SELLER_LOGIN = "/api/v2/auth/sellers/login";
	private static final String EMAIL = "member@example.com";
	private static final String STAFF_EMAIL = "staff@example.com";
	private static final String NEW_PASSWORD = "newPassw0rd!";
	private static final String WRONG = "wrong-password1!";

	private Cookie signupUser() throws Exception {
		return sessionCookie(postJson("/api/v2/auth/users/signup", userSignupBody(EMAIL))
			.andExpect(status().isCreated()).andReturn());
	}

	private void signupApprovedSeller() throws Exception {
		postJson("/api/v1/auth/sellers/signup", sellerSignupBody(EMAIL, "1234567890")).andExpect(status().isCreated());
		jdbc.update("UPDATE seller_business SET status = 'APPROVED'");
	}

	private ResultActions login(String url, String email, String password) throws Exception {
		return postJson(url, loginBody(email, password));
	}

	private Cookie sellerSession(String email) throws Exception {
		return sessionCookie(login(SELLER_LOGIN, email, PASSWORD).andExpect(status().isOk()).andReturn());
	}

	private ResultActions change(String url, Cookie session, String current, String next) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("currentPassword", current);
		body.put("newPassword", next);
		body.put("newPasswordConfirm", next);
		return mockMvc.perform(patch(url).cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String userPasswordHash() {
		return jdbc.queryForObject("SELECT password FROM user_member WHERE email = ?", String.class, EMAIL);
	}

	@Test
	@DisplayName("예매자·판매자가 비밀번호를 바꾸면 새 비밀번호로만 로그인되고, 지금 로그인은 유지되며 알림 메일이 간다")
	void changesPassword() throws Exception {
		Cookie user = signupUser();
		signupApprovedSeller();
		Cookie seller = sellerSession(EMAIL);

		change(USER_URL, user, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());
		// 같은 이메일이어도 예매자 변경은 판매자 계정을 건드리지 않는다
		login(SELLER_LOGIN, EMAIL, PASSWORD).andExpect(status().isOk());
		change(SELLER_URL, seller, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

		login(USER_LOGIN, EMAIL, NEW_PASSWORD).andExpect(status().isOk());
		login(USER_LOGIN, EMAIL, PASSWORD).andExpect(status().isUnauthorized());
		login(SELLER_LOGIN, EMAIL, NEW_PASSWORD).andExpect(status().isOk());
		login(SELLER_LOGIN, EMAIL, PASSWORD).andExpect(status().isUnauthorized());
		// 쓰던 세션으로 한 번 더 바꿀 수 있다
		change(USER_URL, user, NEW_PASSWORD, "otherPassw0rd!").andExpect(status().isOk());
		change(SELLER_URL, seller, NEW_PASSWORD, "otherPassw0rd!").andExpect(status().isOk());
		assertThat(fakeEmailSender.changedNoticeCount(EMAIL)).isEqualTo(4);
	}

	@Test
	@DisplayName("현재 비밀번호가 틀리면 400 AUTH311 이고 비밀번호는 그대로다")
	void wrongCurrentPassword() throws Exception {
		Cookie user = signupUser();
		String before = userPasswordHash();

		change(USER_URL, user, WRONG, NEW_PASSWORD).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH311"));

		assertThat(userPasswordHash()).isEqualTo(before);
		assertThat(fakeEmailSender.changedNoticeCount(EMAIL)).isZero();
	}

	@Test
	@DisplayName("변경에서 틀린 횟수는 로그인 실패와 합쳐 세어 5번이면 로그인도 429 AUTH004 이고, 변경에 성공해도 횟수는 지워지지 않는다")
	void failuresAreSharedWithLogin() throws Exception {
		Cookie user = signupUser();
		login(USER_LOGIN, EMAIL, WRONG).andExpect(status().isUnauthorized());
		for (int i = 0; i < 3; i++) {
			change(USER_URL, user, WRONG, NEW_PASSWORD).andExpect(status().isBadRequest());
		}
		change(USER_URL, user, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());
		change(USER_URL, user, WRONG, "otherPassw0rd!").andExpect(status().isBadRequest());

		login(USER_LOGIN, EMAIL, NEW_PASSWORD).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("AUTH004"));
	}

	@Test
	@DisplayName("잠긴 동안은 현재 비밀번호가 맞아도 429 AUTH004 이고 비밀번호는 그대로다")
	void lockedEvenWithCorrectPassword() throws Exception {
		Cookie user = signupUser();
		String before = userPasswordHash();
		for (int i = 0; i < 5; i++) {
			change(USER_URL, user, WRONG, NEW_PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH311"));
		}

		change(USER_URL, user, PASSWORD, NEW_PASSWORD).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("AUTH004"));

		assertThat(userPasswordHash()).isEqualTo(before);
	}

	@Test
	@DisplayName("같은 계정을 동시에 변경하면 한 번만 성공하고, 성공한 요청의 비밀번호만 남는다")
	void concurrentChangesSucceedOnce() throws Exception {
		Cookie user = signupUser();
		// 실패가 잠금 횟수(5)에 닿지 않는 수로 보낸다
		int threads = 4;

		List<Integer> statuses = runConcurrently(threads,
			i -> () -> change(USER_URL, user, PASSWORD, NEW_PASSWORD + i).andReturn().getResponse().getStatus());

		assertThat(statuses).containsExactlyInAnyOrder(200, 400, 400, 400);
		login(USER_LOGIN, EMAIL, NEW_PASSWORD + statuses.indexOf(200)).andExpect(status().isOk());
		assertThat(fakeEmailSender.changedNoticeCount(EMAIL)).isEqualTo(1);
	}

	@Test
	@DisplayName("담당자도 자기 비밀번호를 바꿀 수 있고, 대표의 비밀번호는 그대로다")
	void staffChangesOwnPassword() throws Exception {
		signupApprovedSeller();
		jdbc.update("""
			INSERT INTO seller_member (business_id, email, password, name, phone, role, status, created_at, updated_at)
			SELECT business_id, ?, password, '담당자', '01033334444', 'STAFF', 'ACTIVE', NOW(), NOW()
			FROM seller_member WHERE email = ?
			""", STAFF_EMAIL, EMAIL);
		Cookie staff = sellerSession(STAFF_EMAIL);

		change(SELLER_URL, staff, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

		login(SELLER_LOGIN, STAFF_EMAIL, NEW_PASSWORD).andExpect(status().isOk());
		login(SELLER_LOGIN, EMAIL, PASSWORD).andExpect(status().isOk());
	}

	@Test
	@DisplayName("변경에 성공하면 그 전에 받은 재설정 링크는 더 쓸 수 없다")
	void revokesResetLinks() throws Exception {
		Cookie user = signupUser();
		postJson("/api/v1/auth/users/password-reset/request", Map.of("email", EMAIL)).andExpect(status().isOk());
		Map<String, Object> token = Map.of("token", fakeEmailSender.lastResetToken(EMAIL));
		postJson("/api/v1/auth/password-reset/validate", token).andExpect(jsonPath("$.data.valid").value(true));

		change(USER_URL, user, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

		postJson("/api/v1/auth/password-reset/validate", token).andExpect(jsonPath("$.data.valid").value(false));
	}
}
