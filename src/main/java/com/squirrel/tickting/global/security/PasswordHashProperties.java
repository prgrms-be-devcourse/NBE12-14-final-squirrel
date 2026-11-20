package com.squirrel.tickting.global.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비밀번호 해시(BCrypt) 동시 계산 상한.
 *
 * @param maxConcurrent 서버 한 대에서 동시에 계산하는 개수
 * @param waitTimeout   자리가 날 때까지 기다리는 시간. 넘으면 503(COMMON005)
 */
@ConfigurationProperties(prefix = "app.auth.password-hash")
public record PasswordHashProperties(int maxConcurrent, Duration waitTimeout) {
}
