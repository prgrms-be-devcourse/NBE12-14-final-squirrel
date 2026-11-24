package com.squirrel.tickting.domain.auth.mail;

import com.squirrel.tickting.domain.auth.service.EmailVerificationProperties;
import com.squirrel.tickting.domain.auth.service.PasswordResetProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 이메일 인증·비밀번호 재설정에 쓰는 부품. 이 기능만 쓰는 것이라 공통 설정(global/config)이 아니라 이 도메인 안에 둔다. */
@Configuration
@EnableConfigurationProperties({EmailVerificationProperties.class, PasswordResetProperties.class})
public class EmailVerificationConfig {

	/**
	 * 하루 발송 한도의 "하루"를 한국 시간 자정으로 세는 시계. 시각을 바꿔 끼울 수 있어야 자정 초기화를 테스트할 수 있어서 빈으로 둔다.
	 * 이 기능 전용이다. 다른 기능이 Clock 빈을 또 만들면 같은 타입이 둘이 되므로 그때 이름으로 구분한다.
	 */
	@Bean
	Clock emailVerificationClock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}

	/** 앱을 끌 때 스프링이 shutdown() 을 불러 대기 중인 발송을 마저 보낸다. */
	@Bean(destroyMethod = "shutdown")
	ThreadPoolEmailSendExecutor emailSendExecutor(EmailVerificationProperties properties, MeterRegistry meterRegistry) {
		ThreadPoolEmailSendExecutor executor = new ThreadPoolEmailSendExecutor(properties.sendThreads(),
			properties.sendQueue());
		Gauge.builder("email.verification.send.queue", executor, ThreadPoolEmailSendExecutor::queueSize)
			.description("이메일 인증번호 발송을 기다리는 작업 수")
			.register(meterRegistry);
		return executor;
	}
}
