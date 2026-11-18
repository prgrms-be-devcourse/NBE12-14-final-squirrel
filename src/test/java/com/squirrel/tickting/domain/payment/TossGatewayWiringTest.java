package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.domain.payment.gateway.PaymentGateway;
import com.squirrel.tickting.domain.payment.gateway.TossClientConfig;
import com.squirrel.tickting.domain.payment.gateway.TossPaymentGateway;
import com.squirrel.tickting.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 토스 구현(app.payment.gateway=toss)을 골랐을 때 빈이 만들어지는지 확인한다.
 * 통합 테스트는 모두 mock 으로 고정되어 있어서, toss 모드에서 앱이 뜨는지는 여기서 따로 본다.
 *
 * TossClientConfig 가 주입받는 RestClient.Builder 는 restclient 스타터(build.gradle)의 자동 설정이 만든다.
 * Spring Boot 4 는 이 자동 설정을 webmvc 스타터에서 분리해서, 스타터 의존성을 빼면 toss 모드에서만 앱이 뜨지 않는다.
 * 그 자동 설정(RestClientAutoConfiguration)만 얹은 최소 컨텍스트에서 확인한다. 스타터를 빼면 이 테스트가 컴파일되지 않는다.
 */
class TossGatewayWiringTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class))
		.withUserConfiguration(TossClientConfig.class, TossPaymentGateway.class)
		.withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

	@Test
	@DisplayName("toss 모드에서는 자동 설정 없이도 토스 클라이언트와 결제 게이트웨이 빈이 만들어진다")
	void tossModeCreatesGateway() {
		runner.withPropertyValues(
				"app.payment.gateway=toss",
				"toss.secret-key=" + TestSecrets.randomTossSecretKey(),
				"toss.base-url=https://api.tosspayments.com",
				"toss.connect-timeout=2s",
				"toss.read-timeout=10s")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(RestClient.class);
				assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(TossPaymentGateway.class);
			});
	}

	@Test
	@DisplayName("toss 모드가 아니면(mock) 토스 클라이언트와 게이트웨이 빈을 만들지 않아서 시크릿 키가 없어도 된다")
	void otherModeCreatesNothing() {
		runner.withPropertyValues("app.payment.gateway=mock")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(RestClient.class);
				assertThat(context).doesNotHaveBean(PaymentGateway.class);
			});
	}
}
