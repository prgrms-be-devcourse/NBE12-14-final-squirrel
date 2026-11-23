package com.squirrel.tickting.domain.captcha.verifier;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 구글 reCAPTCHA 연동 설정. 값은 application.yaml 의 recaptcha 에 있고, 비밀 값은 .env 에서 읽는다.
 *
 * @param baseUrl        구글 주소. 테스트에서는 가짜 서버 주소로 바꾼다
 * @param secretKey      서버 전용 비밀 키. 브라우저로 내려보내거나 로그에 남기면 안 된다. .env 의 RECAPTCHA_SECRET_KEY
 * @param connectTimeout 연결 시간 제한
 * @param readTimeout    응답을 기다리는 시간 제한. 넘으면 캡챠 서버 장애로 본다
 */
@ConfigurationProperties(prefix = "recaptcha")
public record RecaptchaProperties(String baseUrl, String secretKey, Duration connectTimeout, Duration readTimeout) {
}
