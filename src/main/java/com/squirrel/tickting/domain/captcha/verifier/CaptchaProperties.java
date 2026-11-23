package com.squirrel.tickting.domain.captcha.verifier;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 캡챠 정책. 값은 application.yaml 의 app.captcha 에 있다.
 *
 * @param verifier 검증 구현. recaptcha 또는 mock
 * @param minScore 이 점수보다 낮으면 사람이 아닌 것으로 본다. reCAPTCHA v3 점수는 0.0(봇)~1.0(사람)이다
 */
@ConfigurationProperties(prefix = "app.captcha")
public record CaptchaProperties(String verifier, double minScore) {
}
