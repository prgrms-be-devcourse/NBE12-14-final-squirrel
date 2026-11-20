package com.squirrel.tickting.global.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 인코더. 로그인 비교(PasswordVerifier)와 가입 해시가 모두 이 빈을 쓰므로
 * 동시 계산 상한을 여기서 한 번 감싸면 빠지는 곳이 없다.
 */
@Configuration
@EnableConfigurationProperties(PasswordHashProperties.class)
public class PasswordEncoderConfig {

	@Bean
	public PasswordEncoder passwordEncoder(PasswordHashProperties properties) {
		return new ConcurrencyLimitedPasswordEncoder(new BCryptPasswordEncoder(),
			properties.maxConcurrent(), properties.waitTimeout());
	}
}
