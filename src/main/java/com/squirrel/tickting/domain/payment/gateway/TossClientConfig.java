package com.squirrel.tickting.domain.payment.gateway;

import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 토스를 부르는 HTTP 클라이언트. 토스 구현(app.payment.gateway=toss)을 고른 때만 만들어서,
 * Mock 으로 돌릴 때는 시크릿 키(.env 의 TOSS_SECRET_KEY)가 없어도 앱이 뜬다.
 * 시간 제한을 꼭 둔다. 없으면 토스가 응답하지 않을 때 요청 스레드가 끝없이 기다린다.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "toss")
@EnableConfigurationProperties(TossProperties.class)
public class TossClientConfig {

	@Bean
	public RestClient tossRestClient(RestClient.Builder builder, TossProperties properties) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
			HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
		requestFactory.setReadTimeout(properties.readTimeout());
		return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
	}
}
