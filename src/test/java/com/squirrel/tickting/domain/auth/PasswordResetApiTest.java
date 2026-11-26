package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 비밀번호 찾기(AUTH-19~22). 실제 PostgreSQL·Redis(Testcontainers)에서 API 를 호출한다.
 * 메일 서버·발송 스레드·시계는 테스트용으로 갈아 끼운다. (EmailTestConfiguration)
 * 진짜 SMTP 로 보내는 코드는 여기서 확인하지 않는다.
 */
class PasswordResetApiTest extends IntegrationTestSupport {

	private static final String USER_REQUEST = "/api/v1/auth/users/password-reset/request";
	private static final String SELLER_REQUEST = "/api/v1/auth/sellers/password-reset/request";
	private static final String VALIDATE = "/api/v1/auth/password-reset/validate";
	private static final String CONFIRM = "/api/v1/auth/password-reset/confirm";
	private static final String EMAIL = "member@example.com";
	private static final String BUSINESS_NUMBER = "1234567890";
	private static final String NEW_PASSWORD = "newPassw0rd!";

	@Autowired
	StringRedisTemplate redis;

	private void signupUser() throws Exception {
		postJson("/api/v2/auth/users/signup", userSignupBody(EMAIL)).andExpect(status().isCreated());
	}

	private void signupSeller() throws Exception {
		postJson("/api/v1/auth/sellers/signup", sellerSignupBody(EMAIL, BUSINESS_NUMBER)).andExpect(status().isCreated());
	}

	private ResultActions requestUser() throws Exception {
		return postJson(USER_REQUEST, Map.of("email", EMAIL));
	}

	private ResultActions requestSeller() throws Exception {
		return postJson(SELLER_REQUEST, Map.of("email", EMAIL));
	}

	private ResultActions validate(String token) throws Exception {
		return postJson(VALIDATE, Map.of("token", token));
	}

	private ResultActions confirm(String token, String password, String passwordConfirm) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("token", token);
		body.put("password", password);
		body.put("passwordConfirm", passwordConfirm);
		return postJson(CONFIRM, body);
	}

	private ResultActions confirm(String token) throws Exception {
		return confirm(token, NEW_PASSWORD, NEW_PASSWORD);
	}

	private ResultActions userLogin(String password) throws Exception {
		return postJson("/api/v2/auth/users/login", loginBody(EMAIL, password));
	}

	/** 재요청 간격(60초)은 Redis 시각으로 흘러서 테스트에서 기다릴 수 없다. 간격 키를 지워 간격이 지난 것으로 만든다. */
	private void passResendInterval() {
		var keys = redis.keys("tickting:pw-reset:*:resend");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private int passwordResetKeyCount() {
		var keys = redis.keys("tickting:pw-reset:*");
		return keys == null ? 0 : keys.size();
	}

	private String userPasswordHash() {
		return jdbc.queryForObject("SELECT password FROM user_member WHERE email = ?", String.class, EMAIL);
	}

	// ---------------------------------------------------------------- 정상 흐름

	@Test
	@DisplayName("예매자가 링크로 비밀번호를 바꾸면 새 비밀번호로만 로그인되고, 변경 알림 메일이 간다")
	void userResetsPassword() throws Exception {
		signupUser();

		requestUser().andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value(EMAIL));
		String token = fakeEmailSender.lastResetToken(EMAIL);
		assertThat(token).isNotBlank();
		assertThat(fakeEmailSender.lastResetType(EMAIL)).isEqualTo(AccountType.USER);

		validate(token).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.valid").value(true))
			.andExpect(jsonPath("$.data.accountType").value("USER"));
		// 확인은 링크를 소모하지 않는다
		validate(token).andExpect(jsonPath("$.data.valid").value(true));

		confirm(token).andExpect(status().isOk());

		userLogin(NEW_PASSWORD).andExpect(status().isOk());
		userLogin(PASSWORD).andExpect(status().isUnauthorized());
		assertThat(fakeEmailSender.changedNoticeCount(EMAIL)).isEqualTo(1);
		validate(token).andExpect(jsonPath("$.data.valid").value(false));
	}

	@Test
	@DisplayName("판매자는 승인 대기 중인 대표라도 재설정 링크를 받고 비밀번호를 바꿀 수 있다")
	void sellerResetsPasswordWhilePending() throws Exception {
		signupSeller();

		requestSeller().andExpect(status().isOk());
		String token = fakeEmailSender.lastResetToken(EMAIL);
		assertThat(fakeEmailSender.lastResetType(EMAIL)).isEqualTo(AccountType.SELLER);
		validate(token).andExpect(jsonPath("$.data.accountType").value("SELLER"));

		confirm(token).andExpect(status().isOk());

		jdbc.update("UPDATE seller_business SET status = 'APPROVED' WHERE business_number = ?", BUSINESS_NUMBER);
		postJson("/api/v2/auth/sellers/login", loginBody(EMAIL, NEW_PASSWORD)).andExpect(status().isOk());
		postJson("/api/v2/auth/sellers/login", loginBody(EMAIL, PASSWORD)).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------- 링크

	@Test
	@DisplayName("같은 링크로 동시에 변경해도 한 번만 성공한다")
	void sameLinkConfirmedConcurrentlySucceedsOnce() throws Exception {
		signupUser();
		requestUser().andExpect(status().isOk());
		String token = fakeEmailSender.lastResetToken(EMAIL);

		List<Integer> statuses = runConcurrently(8, i -> () -> confirm(token).andReturn().getResponse().getStatus());

		assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s == 400)).hasSize(7);
		assertThat(fakeEmailSender.changedNoticeCount(EMAIL)).isEqualTo(1);
		userLogin(NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	@DisplayName("새 링크를 받아도 이전 링크는 유효하고, 하나로 비밀번호를 바꾸면 그 계정의 다른 링크는 모두 폐기된다")
	void changingPasswordRevokesOtherLinks() throws Exception {
		signupUser();
		requestUser().andExpect(status().isOk());
		String first = fakeEmailSender.lastResetToken(EMAIL);
		passResendInterval();
		requestUser().andExpect(status().isOk());
		String second = fakeEmailSender.lastResetToken(EMAIL);
		assertThat(second).isNotEqualTo(first);
		validate(first).andExpect(jsonPath("$.data.valid").value(true));

		confirm(second).andExpect(status().isOk());

		validate(first).andExpect(jsonPath("$.data.valid").value(false));
		confirm(first, "otherPassw0rd!", "otherPassw0rd!").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH309"));
		userLogin(NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	@DisplayName("같은 이메일이 예매자와 판매자로 모두 가입돼 있어도 링크는 요청한 종류의 계정만 바꾼다")
	void linkChangesOnlyTheRequestedAccountType() throws Exception {
		signupUser();
		signupSeller();
		String sellerHash = jdbc.queryForObject("SELECT password FROM seller_member WHERE email = ?", String.class, EMAIL);

		requestUser().andExpect(status().isOk());
		String token = fakeEmailSender.lastResetToken(EMAIL);
		confirm(token).andExpect(status().isOk());

		userLogin(NEW_PASSWORD).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT password FROM seller_member WHERE email = ?", String.class, EMAIL))
			.isEqualTo(sellerHash);
	}

	@Test
	@DisplayName("만료되었거나 없는 링크는 확인에서 valid=false 이고, 변경은 400 AUTH309 이며 비밀번호는 그대로다")
	void expiredOrUnknownLink() throws Exception {
		signupUser();
		requestUser().andExpect(status().isOk());
		String token = fakeEmailSender.lastResetToken(EMAIL);
		String before = userPasswordHash();
		// 유효 시간이 지나 Redis 가 지운 것과 같다
		var keys = redis.keys("tickting:pw-reset:token:*");
		redis.delete(keys);

		validate(token).andExpect(status().isOk()).andExpect(jsonPath("$.data.valid").value(false))
			.andExpect(jsonPath("$.data.accountType").doesNotExist());
		confirm(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH309"));
		confirm("not-a-real-token").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH309"));

		assertThat(userPasswordHash()).isEqualTo(before);
	}

	@Test
	@DisplayName("비밀번호 확인이 다르면 링크를 쓰지 않고 400 AUTH005 이다. 같은 링크로 다시 시도할 수 있다")
	void confirmMismatchDoesNotConsumeLink() throws Exception {
		signupUser();
		requestUser().andExpect(status().isOk());
		String token = fakeEmailSender.lastResetToken(EMAIL);

		confirm(token, NEW_PASSWORD, "different1!").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH005"));

		validate(token).andExpect(jsonPath("$.data.valid").value(true));
		confirm(token).andExpect(status().isOk());
	}

	@Test
	@DisplayName("링크를 받은 뒤 계정이 탈퇴하면 변경은 400 AUTH309 이고 탈퇴한 계정에 비밀번호가 생기지 않는다")
	void withdrawnAccountIsNotChanged() throws Exception {
		signupUser();
		requestUser().andExpect(status().isOk());
		String token = fakeEmailSender.lastResetToken(EMAIL);
		jdbc.update("UPDATE user_member SET deleted_at = now(), password = 'WITHDRAWN' WHERE email = ?", EMAIL);

		confirm(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH309"));

		assertThat(userPasswordHash()).isEqualTo("WITHDRAWN");
		assertThat(fakeEmailSender.changedNoticeCount(EMAIL)).isZero();
	}

	// ---------------------------------------------------------------- 요청 제한

	@Test
	@DisplayName("가입되지 않은 이메일은 404 AUTH308 이고, 간격·하루 한도 키를 만들지 않는다")
	void unknownEmailCreatesNoKeys() throws Exception {
		signupUser();

		postJson(USER_REQUEST, Map.of("email", "nobody@example.com")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("AUTH308"));
		// 예매자로만 가입했으니 같은 이메일도 판매자 쪽에서는 없는 이메일이다
		requestSeller().andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AUTH308"));

		assertThat(passwordResetKeyCount()).isZero();
		assertThat(fakeEmailSender.resetLinkCount("nobody@example.com")).isZero();
	}

	@Test
	@DisplayName("재요청 간격 안이면 429 AUTH302 이고 메일이 한 통만 간다. 동시에 눌러도 한 통이다")
	void resendIntervalAndConcurrentRequests() throws Exception {
		signupUser();

		List<Integer> statuses = runConcurrently(8, i -> () -> requestUser().andReturn().getResponse().getStatus());

		assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s == 429)).hasSize(7);
		assertThat(fakeEmailSender.resetLinkCount(EMAIL)).isEqualTo(1);
		requestUser().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH302"));
	}

	@Test
	@DisplayName("하루 10회를 넘기면 429 AUTH303 이다")
	void dailyLimit() throws Exception {
		signupUser();
		for (int i = 0; i < 10; i++) {
			passResendInterval();
			requestUser().andExpect(status().isOk());
		}

		passResendInterval();
		requestUser().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH303"));
		assertThat(fakeEmailSender.resetLinkCount(EMAIL)).isEqualTo(10);
	}

	@Test
	@DisplayName("발송 대기가 가득 차 접수하지 못하면 503 AUTH304 이고, 링크·횟수·간격을 되돌려 바로 다시 요청할 수 있다")
	void queueFullRollsBack() throws Exception {
		signupUser();
		emailSendExecutor.setRejecting(true);

		requestUser().andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH304"));

		// 링크·간격·계정 집합은 사라지고, 하루 횟수 키는 0 으로 남는다 (지난 날짜 키를 치우는 만료가 걸려 있다)
		assertThat(redis.keys("tickting:pw-reset:token:*")).isEmpty();
		assertThat(redis.keys("tickting:pw-reset:acct:*")).isEmpty();
		assertThat(redis.keys("tickting:pw-reset:*:resend")).isEmpty();
		var dailyKeys = redis.keys("tickting:pw-reset:USER:*:daily:*");
		assertThat(dailyKeys).hasSize(1);
		assertThat(redis.opsForValue().get(dailyKeys.iterator().next())).isEqualTo("0");
		emailSendExecutor.setRejecting(false);
		requestUser().andExpect(status().isOk());
		assertThat(fakeEmailSender.resetLinkCount(EMAIL)).isEqualTo(1);
	}

	@Test
	@DisplayName("접수 뒤 발송이 실패하면 그 링크는 폐기되고 하루 횟수는 돌아오지만, 재요청 간격은 그대로다")
	void sendFailureDiscardsLinkButKeepsInterval() throws Exception {
		signupUser();
		fakeEmailSender.setFailing(true);

		requestUser().andExpect(status().isOk());

		assertThat(fakeEmailSender.resetLinkCount(EMAIL)).isZero();
		var dailyKeys = redis.keys("tickting:pw-reset:USER:*:daily:*");
		assertThat(dailyKeys).hasSize(1);
		assertThat(redis.opsForValue().get(dailyKeys.iterator().next())).isEqualTo("0");
		requestUser().andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH302"));
		passResendInterval();
		fakeEmailSender.setFailing(false);
		requestUser().andExpect(status().isOk());
		validate(fakeEmailSender.lastResetToken(EMAIL)).andExpect(jsonPath("$.data.valid").value(true));
	}
}
