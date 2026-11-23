package com.squirrel.tickting.domain.captcha.verifier;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 구글 reCAPTCHA 를 부르는 HTTP 클라이언트. recaptcha 구현(app.captcha.verifier=recaptcha)을 고른 때만 만들어서,
 * Mock 으로 돌릴 때는 비밀 키(.env 의 RECAPTCHA_SECRET_KEY)가 없어도 앱이 뜬다.
 * 시간 제한을 꼭 둔다. 없으면 구글이 응답하지 않을 때 대기열 줄 서기 요청이 톰캣 스레드를 붙잡고 끝없이 기다린다.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.captcha", name = "verifier", havingValue = "recaptcha")
@EnableConfigurationProperties({RecaptchaProperties.class, CaptchaProperties.class})
public class RecaptchaClientConfig {

	@Bean
	public RestClient recaptchaRestClient(RestClient.Builder builder, RecaptchaProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
		requestFactory.setReadTimeout(properties.readTimeout());
		return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
	}
}
