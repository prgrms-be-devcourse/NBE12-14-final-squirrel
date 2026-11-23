package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
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
 * 이메일 인증번호 발송·확인(AUTH-17·18). 실제 PostgreSQL·Redis(Testcontainers)에서 API 를 호출한다.
 * 메일 서버·발송 스레드·시계는 테스트용으로 갈아 끼운다. (EmailTestConfiguration)
 * 진짜 SMTP 발송 코드는 SmtpEmailSenderTest 가 따로 확인한다.
 */
class EmailVerificationApiTest extends IntegrationTestSupport {

	private static final String SEND = "/api/v1/users/me/email-verification";
	private static final String CONFIRM = SEND + "/confirm";
	private static final String EMAIL = "member@example.com";

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	MeterRegistry meterRegistry;

	private record Member(Long id, String bearer) {
	}

	private Member signup() throws Exception {
		MvcResult result = postJson("/api/v1/auth/users/signup", userSignupBody(EMAIL)).andExpect(status().isCreated())
			.andReturn();
		Long id = jdbc.queryForObject("SELECT id FROM user_member WHERE email = ?", Long.class, EMAIL);
		return new Member(id, "Bearer " + accessToken(result));
	}

	private ResultActions send(Member member) throws Exception {
		return mockMvc.perform(post(SEND).header("Authorization", member.bearer()));
	}

	private ResultActions confirm(Member member, String code) throws Exception {
		return mockMvc.perform(post(CONFIRM)
			.header("Authorization", member.bearer())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + code + "\"}"));
	}

	/** 재발송 간격(60초)은 Redis 시각으로 흘러서 테스트에서 기다릴 수 없다. 키를 지워 간격이 지난 것으로 만든다. */
	private void passResendInterval(Member member) {
		redis.delete("tickting:email-verify:USER:{" + member.id() + "}:resend");
	}

	private static String wrongCodeFor(String code) {
		return "000000".equals(code) ? "111111" : "000000";
	}

	private boolean emailVerified(Member member) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
			"SELECT email_verified_at IS NOT NULL FROM user_member WHERE id = ?", Boolean.class, member.id()));
	}

	// ---------------------------------------------------------------- 발송·확인

	@Test
	@DisplayName("인증번호를 받아 확인하면 인증 시각이 남고, 내 정보의 emailVerified 가 true 가 된다. 다시 로그인하지 않아도 된다")
	void sendAndConfirm() throws Exception {
		Member member = signup();
		mockMvc.perform(get("/api/v1/users/me").header("Authorization", member.bearer()))
			.andExpect(jsonPath("$.data.emailVerified").value(false));

		send(member)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.expiresAt").exists())
			.andExpect(jsonPath("$.data.resendAvailableAt").exists());
		String code = fakeEmailSender.lastCode(EMAIL);
		assertThat(code).matches("[0-9]{6}");
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);

		confirm(member, code)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.emailVerifiedAt").exists());

		assertThat(emailVerified(member)).isTrue();
		mockMvc.perform(get("/api/v1/users/me").header("Authorization", member.bearer()))
			.andExpect(jsonPath("$.data.emailVerified").value(true));
	}

	@Test
	@DisplayName("인증번호의 유효 시간과 재발송 시각이 설정한 값(5분, 60초)으로 저장된다")
	void expiryFollowsConfiguration() throws Exception {
		Member member = signup();

		MvcResult result = send(member).andExpect(status().isOk()).andReturn();

		Long ttl = redis.getExpire("tickting:email-verify:USER:{" + member.id() + "}:code");
		assertThat(ttl).isBetween(295L, 300L);
		LocalDateTime expiresAt = LocalDateTime.parse(read(result, "$.data.expiresAt"));
		LocalDateTime resendAt = LocalDateTime.parse(read(result, "$.data.resendAvailableAt"));
		assertThat(java.time.Duration.between(resendAt, expiresAt)).isEqualTo(java.time.Duration.ofSeconds(240));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "12345", "1234567", "12345a", "abcdef"})
	@DisplayName("숫자 6자리가 아닌 인증번호는 400 이다 (인증번호를 보지 않고 돌려보낸다)")
	void rejectsMalformedCode(String code) throws Exception {
		Member member = signup();
		send(member).andExpect(status().isOk());

		confirm(member, code)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));

		// 형식 오류는 틀린 횟수에 넣지 않는다
		assertThat(redis.opsForHash().get("tickting:email-verify:USER:{" + member.id() + "}:code", "fails"))
			.isEqualTo("0");
	}

	@Test
	@DisplayName("인증번호를 받은 적이 없으면 확인이 400(AUTH306)이다")
	void confirmWithoutSending() throws Exception {
		Member member = signup();

		confirm(member, "123456")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH306"));
	}

	@Test
	@DisplayName("틀리면 남은 횟수가 메시지에 나오고, 5번째로 틀리면 번호가 폐기되어 맞는 번호도 통하지 않는다")
	void wrongCodeExhaustsAttempts() throws Exception {
		Member member = signup();
		send(member).andExpect(status().isOk());
		String code = fakeEmailSender.lastCode(EMAIL);
		String wrong = wrongCodeFor(code);

		for (int remaining = 4; remaining >= 1; remaining--) {
			confirm(member, wrong)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH305"))
				.andExpect(jsonPath("$.message").value("인증번호가 올바르지 않습니다. (남은 횟수 " + remaining + "회)"));
		}
		confirm(member, wrong)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH306"));
		confirm(member, code)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH306"));
		assertThat(emailVerified(member)).isFalse();
	}

	@Test
	@DisplayName("다시 받으면 이전 인증번호는 바로 무효가 된다")
	void resendInvalidatesPreviousCode() throws Exception {
		Member member = signup();
		send(member).andExpect(status().isOk());
		String first = fakeEmailSender.lastCode(EMAIL);
		passResendInterval(member);
		send(member).andExpect(status().isOk());
		String second = fakeEmailSender.lastCode(EMAIL);
		assumeThat(first).isNotEqualTo(second);

		confirm(member, first).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH305"));
		confirm(member, second).andExpect(status().isOk());
	}

	@Test
	@DisplayName("이미 인증한 회원은 발송·확인이 모두 409(AUTH301)이고 메일을 보내지 않는다")
	void alreadyVerified() throws Exception {
		Member member = signup();
		verifyEmail(EMAIL, member.bearer());
		int before = fakeEmailSender.sentCount(EMAIL);
		passResendInterval(member);

		send(member).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUTH301"));
		confirm(member, "123456").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUTH301"));

		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(before);
	}

	@Test
	@DisplayName("정지된 회원은 발송·확인이 401(AUTH003)이다")
	void suspendedMember() throws Exception {
		Member member = signup();
		jdbc.update("UPDATE user_member SET status = 'SUSPENDED' WHERE id = ?", member.id());

		send(member).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH003"));
		confirm(member, "123456").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH003"));
		assertThat(fakeEmailSender.sentCount(EMAIL)).isZero();
	}

	@Test
	@DisplayName("탈퇴한 회원의 토큰은 발송이 404(USER002)이다")
	void withdrawnMember() throws Exception {
		Member member = signup();
		jdbc.update("UPDATE user_member SET deleted_at = now() WHERE id = ?", member.id());

		send(member).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER002"));
	}

	@Test
	@DisplayName("로그인하지 않았거나 판매자 토큰이면 발송할 수 없다")
	void onlyUsersCanSend() throws Exception {
		mockMvc.perform(post(SEND)).andExpect(status().isUnauthorized());

		postJson("/api/v1/auth/sellers/signup", sellerSignupBody("owner@company.com", "1234567890"))
			.andExpect(status().isCreated());
		jdbc.update("UPDATE seller_business SET status = 'APPROVED'");
		MvcResult login = postJson("/api/v1/auth/sellers/login", loginBody("owner@company.com", PASSWORD))
			.andExpect(status().isOk()).andReturn();
		mockMvc.perform(post(SEND).header("Authorization", "Bearer " + accessToken(login)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	// ---------------------------------------------------------------- 횟수 제한

	@Test
	@DisplayName("60초 안에 다시 보내면 429(AUTH302)이고 메일은 한 통만 간다")
	void resendTooSoon() throws Exception {
		Member member = signup();
		send(member).andExpect(status().isOk());

		send(member).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH302"));

		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
	}

	@Test
	@DisplayName("하루 10번을 넘으면 429(AUTH303)이고, 한국 시간 자정이 지나면 다시 보낼 수 있다")
	void dailyLimitResetsAtMidnight() throws Exception {
		Member member = signup();
		emailClock.setNow(LocalDateTime.of(2026, 11, 24, 23, 59, 50));

		for (int i = 0; i < 10; i++) {
			passResendInterval(member);
			send(member).andExpect(status().isOk());
		}
		passResendInterval(member);
		send(member).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH303"));
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(10);

		emailClock.setNow(LocalDateTime.of(2026, 11, 25, 0, 0, 5));
		send(member).andExpect(status().isOk());
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(11);
	}

	// ---------------------------------------------------------------- 비동기 발송

	@Test
	@DisplayName("발송이 접수만 된 상태에서도 바로 응답하고, 번호는 저장되어 있다 (메일은 뒤에서 나간다)")
	void respondsBeforeMailIsSent() throws Exception {
		Member member = signup();
		emailSendExecutor.setHolding(true);

		send(member).andExpect(status().isOk());

		assertThat(fakeEmailSender.sentCount(EMAIL)).isZero();
		emailSendExecutor.runAllHeld();
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
		confirm(member, fakeEmailSender.lastCode(EMAIL)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("발송이 나중에 실패하면 하루 횟수는 돌려주고 번호는 폐기하되 60초 간격은 그대로다")
	void lateFailureRefundsCountButKeepsInterval() throws Exception {
		Member member = signup();
		fakeEmailSender.setFailing(true);
		double failuresBefore = meterRegistry.counter("email.verification.send", "result", "failure").count();

		send(member).andExpect(status().isOk());

		assertThat(meterRegistry.counter("email.verification.send", "result", "failure").count())
			.isEqualTo(failuresBefore + 1);
		assertThat(redis.hasKey("tickting:email-verify:USER:{" + member.id() + "}:code")).isFalse();
		assertThat(dailyCount(member)).isZero();
		send(member).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("AUTH302"));

		fakeEmailSender.setFailing(false);
		passResendInterval(member);
		send(member).andExpect(status().isOk());
		confirm(member, fakeEmailSender.lastCode(EMAIL)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("늦게 실패한 첫 발송이 그사이 다시 받아서 만든 새 인증번호를 지우지 않는다")
	void lateFailureDoesNotEraseNewerCode() throws Exception {
		Member member = signup();
		emailSendExecutor.setHolding(true);
		send(member).andExpect(status().isOk());
		passResendInterval(member);
		send(member).andExpect(status().isOk());

		// 먼저 접수한 발송만 실패하고, 두 번째 발송은 정상으로 나간다
		fakeEmailSender.setFailing(true);
		emailSendExecutor.runFirstHeld();
		fakeEmailSender.setFailing(false);
		emailSendExecutor.runAllHeld();

		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
		confirm(member, fakeEmailSender.lastCode(EMAIL)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("발송 대기가 가득 차면 503(AUTH304)이고, 번호·횟수·간격이 되돌아가서 바로 다시 보낼 수 있다")
	void queueFullRollsEverythingBack() throws Exception {
		Member member = signup();
		emailSendExecutor.setRejecting(true);

		send(member).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH304"));

		assertThat(redis.hasKey("tickting:email-verify:USER:{" + member.id() + "}:code")).isFalse();
		assertThat(redis.hasKey("tickting:email-verify:USER:{" + member.id() + "}:resend")).isFalse();
		assertThat(dailyCount(member)).isZero();

		emailSendExecutor.setRejecting(false);
		send(member).andExpect(status().isOk());
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
	}

	private long dailyCount(Member member) {
		List<String> keys = redis.keys("tickting:email-verify:USER:{" + member.id() + "}:daily:*").stream().toList();
		assertThat(keys).hasSize(1);
		return Long.parseLong(redis.opsForValue().get(keys.get(0)));
	}

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("같은 인증번호로 동시에 확인해도 한 번만 성공한다")
	void concurrentConfirmSucceedsOnce() throws Exception {
		Member member = signup();
		send(member).andExpect(status().isOk());
		String code = fakeEmailSender.lastCode(EMAIL);

		List<Integer> statuses = runConcurrently(8, i -> () -> confirm(member, code).andReturn().getResponse().getStatus());

		assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
		// 나머지는 번호가 이미 지워졌거나(400), 이미 인증됐다(409)
		assertThat(statuses.stream().filter(s -> s != 200)).allMatch(s -> s == 400 || s == 409);
		assertThat(emailVerified(member)).isTrue();
	}

	@Test
	@DisplayName("같은 회원이 동시에 발송을 요청해도 메일은 한 통만 나가고 나머지는 429(AUTH302)이다")
	void concurrentSendSendsOneMail() throws Exception {
		Member member = signup();

		List<Integer> statuses = runConcurrently(8, i -> () -> send(member).andReturn().getResponse().getStatus());

		assertThat(statuses.stream().filter(s -> s == 200)).hasSize(1);
		assertThat(statuses.stream().filter(s -> s == 429)).hasSize(7);
		assertThat(fakeEmailSender.sentCount(EMAIL)).isEqualTo(1);
		assertThat(dailyCount(member)).isEqualTo(1);
	}
}
