package com.squirrel.tickting.domain.captcha.verifier;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CaptchaExceptionCode;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 구글 reCAPTCHA v3 로 토큰을 확인한다. (siteverify API)
 *
 * <p>구글이 success 를 줘도 그것만 믿지 않는다. 토큰을 받은 화면(action)이 맞는지, 점수(score)가 기준 이상인지도 본다.
 * success 는 "구글이 만든 진짜 토큰"이라는 뜻일 뿐이고, 봇이 받은 토큰도 점수만 낮을 뿐 success 다.
 *
 * <p>구글에 물어볼 수 없으면(연결 실패, 시간 초과, 5xx) 통과시키지 않고 CAPTCHA001(503)로 거절한다.
 * 통과시키면 구글 장애 동안 매크로가 그대로 들어오기 때문이다. (임시 정책. 장애 때 예매가 멈추는 대가가 있어 팀이 다시 정한다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.captcha", name = "verifier", havingValue = "recaptcha")
public class RecaptchaVerifier implements CaptchaVerifier {

	private static final String VERIFY_PATH = "/recaptcha/api/siteverify";
	/** 우리 비밀 키 설정이 잘못됐다는 응답. 사용자 탓이 아니라서 403 이 아니라 503 으로 돌려주고 크게 남긴다 */
	private static final Set<String> CONFIG_ERROR_CODES = Set.of("missing-input-secret", "invalid-input-secret");

	private final RestClient recaptchaRestClient;
	private final RecaptchaProperties recaptchaProperties;
	private final CaptchaProperties captchaProperties;

	@Override
	public boolean verify(String token, String action) {
		// 토큰이 없으면 구글에 묻지 않는다. 무료 사용량을 아끼고, 어차피 실패다
		if (token == null || token.isBlank()) {
			return false;
		}

		SiteVerifyResponse body = requestVerify(token);
		if (!body.success()) {
			List<String> errorCodes = body.errorCodes() == null ? List.of() : body.errorCodes();
			if (errorCodes.stream().anyMatch(CONFIG_ERROR_CODES::contains)) {
				log.error("reCAPTCHA 비밀 키 설정 오류: {}", errorCodes);
				throw new BusinessException(CaptchaExceptionCode.CAPTCHA_UNAVAILABLE);
			}
			// 토큰 값은 남기지 않고 구글이 준 실패 이유만 남긴다 (만료·재사용이면 timeout-or-duplicate)
			log.info("reCAPTCHA 실패: {}", errorCodes);
			return false;
		}
		if (!action.equals(body.action())) {
			log.info("reCAPTCHA action 불일치: 기대 {}, 받은 값 {}", action, body.action());
			return false;
		}
		return body.score() != null && body.score() >= captchaProperties.minScore();
	}

	private SiteVerifyResponse requestVerify(String token) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("secret", recaptchaProperties.secretKey());
		form.add("response", token);
		try {
			SiteVerifyResponse body = recaptchaRestClient.post()
				.uri(VERIFY_PATH)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(SiteVerifyResponse.class);
			if (body == null) {
				throw new BusinessException(CaptchaExceptionCode.CAPTCHA_UNAVAILABLE);
			}
			return body;
		} catch (RestClientException e) {
			// 예외 메시지에 요청 본문(비밀 키)이 섞일 수 있어 종류만 남긴다
			log.warn("reCAPTCHA 검증 요청 실패: {}", e.getClass().getSimpleName());
			throw new BusinessException(CaptchaExceptionCode.CAPTCHA_UNAVAILABLE);
		}
	}

	/** siteverify 응답 중 쓰는 값만 받는다. score·action 은 v3 에서만 온다 */
	record SiteVerifyResponse(
		boolean success,
		Double score,
		String action,
		@JsonProperty("error-codes") List<String> errorCodes) {
	}
}
