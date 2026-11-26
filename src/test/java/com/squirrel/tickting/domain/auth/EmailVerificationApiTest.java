package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.captcha.interceptor.CaptchaInterceptor;
import com.squirrel.tickting.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 가입 전 이메일 인증번호 발송·확인(AUTH-17·18)과 가입의 인증 확인(AUTH-02·16). 실제 PostgreSQL·Redis(Testcontainers)에서 API 를 호출한다.
 * 메일 서버·발송 스레드·시계는 테스트용으로 갈아 끼운다. (EmailTestConfiguration)
 * 진짜 SMTP 발송 코드는 SmtpEmailSenderTest 가 따로 확인한다.
 */
class EmailVerificationApiTest extends IntegrationTestSupport {

	private static final String SEND = "/api/v1/auth/users/email-verification";
	private static final String CONFIRM = SEND + "/confirm";
	private static final String SIGNUP = "/api/v2/auth/users/signup";
	private static final String EMAIL = "member@example.com";
	private static final String KEY_PREFIX = "tickting:email-verify:USER:";

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	MeterRegistry meterRegistry;

	/** 발송은 캡챠를 거친다. 테스트의 Mock 검증기는 토큰이 있으면 통과시킨다. */
	private ResultActions send(String email) throws Exception {
		return mockMvc.perform(post(SEND)
			.header(CaptchaInterceptor.TOKEN_HEADER, "pass")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("email", email))));
	}

	private ResultActions confirm(String email, String code) throws Exception {
		return postJson(CONFIRM, Map.of("email", email, "code", code));
	}

	/** 이 테스트의 이메일 하나에 대한 Redis 키를 찾는다. 키 이름에 이메일 해시가 들어 있어서 끝 부분으로 찾는다. */
	private Set<String> keys(String suffix) {
		return redis.keys(KEY_PREFIX + "*:" + suffix);
	}

	/** 재발송 간격(60초)은 Redis 시각으로 흘러서 테스트에서 기다릴 수 없다. 키를 지워 간격이 지난 것으로 만든다. */
	private void passResendInterval() {
		redis.delete(keys("resend"));
	}

	private void verify(String email) throws Exception {
		send(email).andExpect(status().isOk());
		confirm(email, fakeEmailSender.lastCode(email)).andExpect(status().isOk());
	}

	private int userCount(String email) {
		return jdbc.queryForObject("SELECT count(*) FROM user_member WHERE email = ?", Integer.class, email);
	}

	private static String wrongCodeFor(String code) {
		return "000000".equals(code) ? "111111" : "000000";
	}

	// ---------------------------------------------------------------- 인증 후 가입

	@Test
	@DisplayName("인증번호를 받아 확인한 이메일로 가입하면 가입 시각으로 인증 시각이 남고, 인증 표시는 지워진다")
	void verifyThenSignup() throws Exception {
		send(EMAIL)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.expiresAt").exists())
			.andExpect(jsonPath("$.data.resendAvailableAt").exists());
		String code = fakeEmailSender.lastCode(EMAIL);
		assertThat(code).matches("[0-9]{6}");
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);

		confirm(EMAIL, code)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.verifiedUntil").exists());

		postJson(SIGNUP, unverifiedUserSignupBody(EMAIL)).andExpect(status().isCreated());

		assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM user_member WHERE email = ?",
			Boolean.class, EMAIL)).isTrue();
		assertThat(keys("verified")).isEmpty();
	}

	@Test
	@DisplayName("인증하지 않고 가입하면 400(AUTH307)이고 회원이 만들어지지 않는다")
	void signupWithoutVerification() throws Exception {
		postJson(SIGNUP, unverifiedUserSignupBody(EMAIL))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH307"));

		assertThat(userCount(EMAIL)).isZero();
	}

	@Test
	@DisplayName("다른 이메일로 인증한 표시로는 가입할 수 없다")
	void verificationIsBoundToEmail() throws Exception {
		verify("other@example.com");

		postJson(SIGNUP, unverifiedUserSignupBody(EMAIL))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH307"));
	}

	@Test
	@DisplayName("인증 표시는 20분 동안만 유효하다")
	void verificationExpiresAfterTwentyMinutes() throws Exception {
		verify(EMAIL);

		Long ttl = redis.getExpire(keys("verified").iterator().next());
		assertThat(ttl).isBetween(1195L, 1200L);

		// 유효 시간이 지난 것으로 만든다
		redis.delete(keys("verified"));
		postJson(SIGNUP, unverifiedUserSignupBody(EMAIL))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH307"));
	}

	@Test
	@DisplayName("가입이 실패하면 인증은 그대로라서 인증을 다시 하지 않고 고쳐서 가입할 수 있다")
	void failedSignupKeepsVerification() throws Exception {
		verify(EMAIL);
		Map<String, Object> wrong = unverifiedUserSignupBody(EMAIL);
		wrong.put("passwordConfirm", "different1!");

		postJson(SIGNUP, wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH005"));

		postJson(SIGNUP, unverifiedUserSignupBody(EMAIL)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("대소문자와 앞뒤 공백이 달라도 같은 이메일로 보고 인증한다")
	void emailIsNormalized() throws Exception {
		send(" Member@Example.com ").andExpect(status().isOk());
		confirm("MEMBER@example.com", fakeEmailSender.lastCode(EMAIL)).andExpect(status().isOk());

		postJson(SIGNUP, unverifiedUserSignupBody("Member@EXAMPLE.com")).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("이미 가입한 이메일은 발송이 409(USER000)이고 메일도 번호도 간격·횟수 키도 만들지 않는다")
	void alreadyRegisteredEmail() throws Exception {
		postJson(SIGNUP, userSignupBody(EMAIL)).andExpect(status().isCreated());

		send(EMAIL).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER000"));

		assertThat(fakeEmailSender.sentCount(EMAIL)).isZero();
		assertThat(redis.keys(KEY_PREFIX + "*")).isEmpty();
	}

	@Test
	@DisplayName("같은 이메일로 동시에 가입해도 한 명만 만들어진다 (나머지는 중복 또는 인증 표시 없음)")
	void concurrentSignupCreatesOne() throws Exception {
		verify(EMAIL);
		Map<String, Object> body = unverifiedUserSignupBody(EMAIL);

		List<Integer> statuses = runConcurrently(6,
			i -> () -> postJson(SIGNUP, body).andReturn().getResponse().getStatus());

		assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s != 201)).allMatch(s -> s == 409 || s == 400);
		assertThat(userCount(EMAIL)).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 캡챠

	@Test
	@DisplayName("발송은 캡챠 토큰이 없거나 실패하면 403(CAPTCHA000)이고 번호·메일·횟수가 생기지 않는다. 확인(confirm)은 토큰이 필요 없다")
	void sendRequiresCaptcha() throws Exception {
		mockMvc.perform(post(SEND).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", EMAIL))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAPTCHA000"));
		mockMvc.perform(post(SEND).header(CaptchaInterceptor.TOKEN_HEADER, "fail")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("email", EMAIL))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CAPTCHA000"));

		assertThat(fakeEmailSender.sentCount(EMAIL)).isZero();
		assertThat(redis.keys(KEY_PREFIX + "*")).isEmpty();
		// 확인은 캡챠 없이 부를 수 있다 (번호가 없어서 AUTH306 이지 CAPTCHA000 이 아니다)
		confirm(EMAIL, "123456").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH306"));
	}

	// ---------------------------------------------------------------- 발송·확인

	@Test
	@DisplayName("인증번호의 유효 시간과 재발송 시각이 설정한 값(5분, 60초)으로 저장된다")
	void expiryFollowsConfiguration() throws Exception {
		MvcResult result = send(EMAIL).andExpect(status().isOk()).andReturn();

		Long ttl = redis.getExpire(keys("code").iterator().next());
		assertThat(ttl).isBetween(295L, 300L);
		LocalDateTime expiresAt = LocalDateTime.parse(read(result, "$.data.expiresAt"));
		LocalDateTime resendAt = LocalDateTime.parse(read(result, "$.data.resendAvailableAt"));
		assertThat(Duration.between(resendAt, expiresAt)).isEqualTo(Duration.ofSeconds(240));
	}

	@Test
	@DisplayName("이메일 형식이 아니면 발송이 400 이다")
	void rejectsMalformedEmail() throws Exception {
		send("not-an-email").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "12345", "1234567", "12345a", "abcdef"})
	@DisplayName("숫자 6자리가 아닌 인증번호는 400 이다 (인증번호를 보지 않고 돌려보낸다)")
	void rejectsMalformedCode(String code) throws Exception {
		send(EMAIL).andExpect(status().isOk());

		confirm(EMAIL, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));

		// 형식 오류는 틀린 횟수에 넣지 않는다
		assertThat(redis.opsForHash().get(keys("code").iterator().next(), "fails")).isEqualTo("0");
	}

	@Test
	@DisplayName("인증번호를 받은 적이 없으면 확인이 400(AUTH306)이다")
	void confirmWithoutSending() throws Exception {
		confirm(EMAIL, "123456").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH306"));
	}

	@Test
	@DisplayName("틀리면 남은 횟수가 메시지에 나오고, 5번째로 틀리면 번호가 폐기되어 맞는 번호도 통하지 않는다")
	void wrongCodeExhaustsAttempts() throws Exception {
		send(EMAIL).andExpect(status().isOk());
		String code = fakeEmailSender.lastCode(EMAIL);
		String wrong = wrongCodeFor(code);

		for (int remaining = 4; remaining >= 1; remaining--) {
			confirm(EMAIL, wrong)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH305"))
				.andExpect(jsonPath("$.message").value("인증번호가 올바르지 않습니다. (남은 횟수 " + remaining + "회)"));
		}
		confirm(EMAIL, wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH306"));
		confirm(EMAIL, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH306"));
		assertThat(keys("verified")).isEmpty();
	}

	@Test
	@DisplayName("다시 받으면 이전 인증번호는 바로 무효가 된다")
	void resendInvalidatesPreviousCode() throws Exception {
		send(EMAIL).andExpect(status().isOk());
		String first = fakeEmailSender.lastCode(EMAIL);
		passResendInterval();
		send(EMAIL).andExpect(status().isOk());
		String second = fakeEmailSender.lastCode(EMAIL);
		assumeThat(first).isNotEqualTo(second);

		confirm(EMAIL, first).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH305"));
		confirm(EMAIL, second).andExpect(status().isOk());
	}

	// ---------------------------------------------------------------- 횟수 제한

	@Test
	@DisplayName("60초 안에 다시 보내면 429(AUTH302)이고 메일은 한 통만 간다")
	void resendTooSoon() throws Exception {
		send(EMAIL).andExpect(status().isOk());

		send(EMAIL).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH302"));

		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
	}

	@Test
	@DisplayName("하루 10번을 넘으면 429(AUTH303)이고, 한국 시간 자정이 지나면 다시 보낼 수 있다")
	void dailyLimitResetsAtMidnight() throws Exception {
		emailClock.setNow(LocalDateTime.of(2026, 11, 24, 23, 59, 50));

		for (int i = 0; i < 10; i++) {
			passResendInterval();
			send(EMAIL).andExpect(status().isOk());
		}
		passResendInterval();
		send(EMAIL).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH303"));
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(10);

		emailClock.setNow(LocalDateTime.of(2026, 11, 25, 0, 0, 5));
		send(EMAIL).andExpect(status().isOk());
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(11);
	}

	// ---------------------------------------------------------------- 비동기 발송

	@Test
	@DisplayName("발송이 접수만 된 상태에서도 바로 응답하고, 번호는 저장되어 있다 (메일은 뒤에서 나간다)")
	void respondsBeforeMailIsSent() throws Exception {
		emailSendExecutor.setHolding(true);

		send(EMAIL).andExpect(status().isOk());

		assertThat(fakeEmailSender.sentCount(EMAIL)).isZero();
		emailSendExecutor.runAllHeld();
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
		confirm(EMAIL, fakeEmailSender.lastCode(EMAIL)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("발송이 나중에 실패하면 하루 횟수는 돌려주고 번호는 폐기하되 60초 간격은 그대로다")
	void lateFailureRefundsCountButKeepsInterval() throws Exception {
		fakeEmailSender.setFailing(true);
		double failuresBefore = meterRegistry.counter("email.verification.send", "result", "failure").count();

		send(EMAIL).andExpect(status().isOk());

		assertThat(meterRegistry.counter("email.verification.send", "result", "failure").count())
			.isEqualTo(failuresBefore + 1);
		assertThat(keys("code")).isEmpty();
		assertThat(dailyCount()).isZero();
		send(EMAIL).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH302"));

		fakeEmailSender.setFailing(false);
		passResendInterval();
		send(EMAIL).andExpect(status().isOk());
		confirm(EMAIL, fakeEmailSender.lastCode(EMAIL)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("늦게 실패한 첫 발송이 그사이 다시 받아서 만든 새 인증번호를 지우지 않는다")
	void lateFailureDoesNotEraseNewerCode() throws Exception {
		emailSendExecutor.setHolding(true);
		send(EMAIL).andExpect(status().isOk());
		passResendInterval();
		send(EMAIL).andExpect(status().isOk());

		// 먼저 접수한 발송만 실패하고, 두 번째 발송은 정상으로 나간다
		fakeEmailSender.setFailing(true);
		emailSendExecutor.runFirstHeld();
		fakeEmailSender.setFailing(false);
		emailSendExecutor.runAllHeld();

		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
		confirm(EMAIL, fakeEmailSender.lastCode(EMAIL)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("발송 대기가 가득 차면 503(AUTH304)이고, 번호·횟수·간격이 되돌아가서 바로 다시 보낼 수 있다")
	void queueFullRollsEverythingBack() throws Exception {
		emailSendExecutor.setRejecting(true);

		send(EMAIL).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH304"));

		assertThat(keys("code")).isEmpty();
		assertThat(keys("resend")).isEmpty();
		assertThat(dailyCount()).isZero();

		emailSendExecutor.setRejecting(false);
		send(EMAIL).andExpect(status().isOk());
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
	}

	private long dailyCount() {
		List<String> daily = redis.keys(KEY_PREFIX + "*:daily:*").stream().toList();
		assertThat(daily).hasSize(1);
		return Long.parseLong(redis.opsForValue().get(daily.get(0)));
	}

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("같은 인증번호로 동시에 확인해도 한 번만 성공한다")
	void concurrentConfirmSucceedsOnce() throws Exception {
		send(EMAIL).andExpect(status().isOk());
		String code = fakeEmailSender.lastCode(EMAIL);

		List<Integer> statuses = runConcurrently(8, i -> () -> confirm(EMAIL, code).andReturn().getResponse().getStatus());

		assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s != 200)).allMatch(s -> s == 400);
		assertThat(keys("verified")).hasSize(1);
	}

	@Test
	@DisplayName("같은 이메일로 동시에 발송을 요청해도 메일은 한 통만 나가고 나머지는 429(AUTH302)이다")
	void concurrentSendSendsOneMail() throws Exception {
		List<Integer> statuses = runConcurrently(8, i -> () -> send(EMAIL).andReturn().getResponse().getStatus());

		assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s == 429)).hasSize(7);
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
		assertThat(dailyCount()).isEqualTo(1);
	}
}
