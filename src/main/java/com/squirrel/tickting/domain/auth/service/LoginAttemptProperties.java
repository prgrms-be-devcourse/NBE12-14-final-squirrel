package com.squirrel.tickting.domain.auth.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 로그인 실패 제한 (app.auth.login.*).
 * 같은 IP 에서 같은 이메일로 maxFailures 번 틀리면 lockDuration 동안 로그인을 막는다.
 */
@ConfigurationProperties(prefix = "app.auth.login")
public record LoginAttemptProperties(int maxFailures, Duration lockDuration) {
}
