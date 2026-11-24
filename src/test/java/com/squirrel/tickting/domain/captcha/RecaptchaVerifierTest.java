package com.squirrel.tickting.domain.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.squirrel.tickting.domain.captcha.verifier.CaptchaProperties;
import com.squirrel.tickting.domain.captcha.verifier.RecaptchaProperties;
import com.squirrel.tickting.domain.captcha.verifier.RecaptchaVerifier;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CaptchaExceptionCode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 구글 reCAPTCHA 확인 호출. 구글 키가 없어도 돌도록 실제 구글 대신 가짜 서버(MockRestServiceServer)를 상대로
 * 요청의 모양(주소·비밀 키·토큰)과 응답을 통과/실패/장애로 바꾸는 규칙을 확인한다.
 * 스프링 컨텍스트를 쓰지 않는다.
 */
class RecaptchaVerifierTest {

	private static final String BASE_URL = "https://www.google.com";
	private static final String VERIFY_URL = BASE_URL + "/recaptcha/api/siteverify";
	private static final String ACTION = "queue_enter";

	/** 비밀 키는 코드에 적지 않고 실행할 때 만든다 */
	private final String secretKey = "test-" + UUID.randomUUID();
	private MockRestServiceServer server;
	private RecaptchaVerifier verifier;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		verifier = new RecaptchaVerifier(builder.build(),
			new RecaptchaProperties(BASE_URL, secretKey, Duration.ofSeconds(2), Duration.ofSeconds(3)),
			new CaptchaProperties("recaptcha", 0.5));
	}

	private void respond(String json) {
		server.expect(requestTo(VERIFY_URL)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
	}

	@Test
	@DisplayName("확인 요청은 siteverify 주소로 비밀 키와 토큰을 폼으로 보내고, 성공·같은 action·기준 이상 점수면 통과한다")
	void passesHumanToken() {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("secret", secretKey);
		form.add("response", "token-1");
		server.expect(requestTo(VERIFY_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().formData(form))
			.andRespond(withSuccess("""
				{"success":true,"score":0.9,"action":"queue_enter","challenge_ts":"2026-11-23T10:00:00Z","hostname":"localhost"}
				""", MediaType.APPLICATION_JSON));

		assertThat(verifier.verify("token-1", ACTION)).isTrue();
		server.verify();
	}

	@Test
	@DisplayName("구글이 성공을 줘도 점수가 기준보다 낮거나 다른 화면의 토큰이면 실패다")
	void rejectsLowScoreAndOtherAction() {
		respond("""
			{"success":true,"score":0.1,"action":"queue_enter"}
			""");
		assertThat(verifier.verify("bot-token", ACTION)).isFalse();
		server.verify();

		server.reset();
		respond("""
			{"success":true,"score":0.9,"action":"login"}
			""");
		assertThat(verifier.verify("login-token", ACTION)).isFalse();
		server.verify();
	}

	@Test
	@DisplayName("만료·재사용된 토큰(success=false)은 실패다. 토큰이 없으면 구글에 묻지 않고 실패다")
	void rejectsInvalidOrMissingToken() {
		respond("""
			{"success":false,"error-codes":["timeout-or-duplicate"]}
			""");
		assertThat(verifier.verify("used-token", ACTION)).isFalse();
		server.verify();

		server.reset();
		assertThat(verifier.verify(null, ACTION)).isFalse();
		assertThat(verifier.verify(" ", ACTION)).isFalse();
		server.verify();
	}

	@Test
	@DisplayName("구글에 물어볼 수 없거나(5xx) 우리 비밀 키 설정이 틀리면 사용자 탓이 아니므로 CAPTCHA001(503)이다")
	void unavailableWhenGoogleFailsOrSecretIsWrong() {
		server.expect(requestTo(VERIFY_URL)).andRespond(withServerError());
		assertThatThrownBy(() -> verifier.verify("token", ACTION))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getExceptionCode()).isEqualTo(CaptchaExceptionCode.CAPTCHA_UNAVAILABLE));

		server.reset();
		respond("""
			{"success":false,"error-codes":["invalid-input-secret"]}
			""");
		assertThatThrownBy(() -> verifier.verify("token", ACTION))
			.isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getExceptionCode()).isEqualTo(CaptchaExceptionCode.CAPTCHA_UNAVAILABLE));
	}
}
