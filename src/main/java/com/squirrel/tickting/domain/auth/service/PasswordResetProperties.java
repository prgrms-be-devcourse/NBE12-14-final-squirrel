package com.squirrel.tickting.domain.auth.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 비밀번호 재설정 (app.auth.password-reset.*). 값은 모두 임시 정책이다.
 * 메일의 보낸 사람 이름·주소와 발송 스레드는 이메일 인증과 같이 쓴다. (EmailVerificationProperties)
 *
 * @param linkTtl        재설정 링크 유효 시간. 메일이 도착해서 새 비밀번호를 제출할 때까지를 모두 포함한다
 * @param resendInterval 같은 계정 종류·이메일로 다시 요청할 수 있기까지의 간격. 발송이 나중에 실패해도 그대로다
 * @param dailyLimit     계정 종류·이메일당 하루 요청 한도. 한국 시간 자정에 초기화된다
 * @param frontendBaseUrl 메일 링크의 앞부분(프론트 주소). 끝에 / 가 있어도 없어도 된다
 */
@ConfigurationProperties(prefix = "app.auth.password-reset")
public record PasswordResetProperties(
	Duration linkTtl,
	Duration resendInterval,
	int dailyLimit,
	String frontendBaseUrl
) {
}
