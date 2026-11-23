package com.squirrel.tickting.domain.auth.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 이메일 인증 (app.auth.email-verification.*). 값은 모두 임시 정책이다.
 *
 * @param codeTtl        인증번호 유효 시간
 * @param verifiedTtl    인증번호를 맞힌 뒤 가입을 마칠 때까지 그 이메일의 인증이 유효한 시간
 * @param maxAttempts    이 횟수만큼 틀리면 그 인증번호를 폐기한다. 새로 받아야 한다
 * @param resendInterval 재발송 간격. 발송이 나중에 실패해도 이 간격은 그대로다
 * @param dailyLimit     하루 발송 한도. 한국 시간 자정에 초기화된다
 * @param senderName     메일의 보낸 사람 이름과 제목에 쓰는 서비스 이름
 * @param fromAddress    메일의 보낸 사람 주소. 운영은 메일 서비스에서 확인을 마친 주소여야 한다 (배포 때 정한다)
 * @param sendThreads    발송 스레드 수. 서버마다 따로 센다
 * @param sendQueue      발송 대기 수. 가득 차면 접수하지 않고 거절한다
 */
@ConfigurationProperties(prefix = "app.auth.email-verification")
public record EmailVerificationProperties(
	Duration codeTtl,
	Duration verifiedTtl,
	int maxAttempts,
	Duration resendInterval,
	int dailyLimit,
	String senderName,
	String fromAddress,
	int sendThreads,
	int sendQueue
) {
}
