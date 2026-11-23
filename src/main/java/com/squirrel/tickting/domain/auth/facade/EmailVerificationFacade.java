package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.auth.dto.EmailVerificationRequest;
import com.squirrel.tickting.domain.auth.dto.EmailVerificationResponse;
import com.squirrel.tickting.domain.auth.mail.EmailSendExecutor;
import com.squirrel.tickting.domain.auth.mail.EmailSender;
import com.squirrel.tickting.domain.auth.service.EmailVerificationCodeService;
import com.squirrel.tickting.domain.auth.service.EmailVerificationCodeService.Issued;
import com.squirrel.tickting.domain.auth.service.EmailVerificationProperties;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 이메일 인증. 회원(user 도메인)과 인증번호·메일(auth 도메인)을 엮는다.
 *
 * <p>발송은 비동기다. 인증번호를 저장하고 발송을 접수하면 바로 응답하고, 메일은 발송 스레드가 뒤에서 보낸다.
 * 인기 공연 오픈 때 인증하는 사람이 몰려도 발송이 요청 스레드를 묶어 대기열·예매까지 밀리지 않게 하려는 것이다.
 * 대신 응답은 "메일이 도착했다"가 아니라 "접수했다"이고, 나중에 발송이 실패해도 응답으로는 알려 주지 못한다.
 * 메일 상태는 발송 결과 지표(email.verification.send)로 본다.
 * 메일 서버를 부르는 동안 DB 트랜잭션을 쥐지 않는다. (파사드는 트랜잭션을 열지 않고, 서비스가 각자 짧게 연다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailVerificationFacade {

	private static final String SEND_METRIC = "email.verification.send";

	private final UserMemberService userMemberService;
	private final EmailVerificationCodeService codeService;
	private final EmailSender emailSender;
	private final EmailSendExecutor sendExecutor;
	private final EmailVerificationProperties properties;
	private final MeterRegistry meterRegistry;
	private final Clock clock;

	/**
	 * 인증번호를 만들어 저장하고 발송을 접수한다.
	 * 확인 순서: 회원(404) → 정지(401) → 이미 인증(409) → 재발송 간격(429) → 하루 한도(429) → 발송 대기(503).
	 * 메일은 요청으로 받지 않고 가입한 이메일로만 보낸다.
	 */
	public EmailVerificationResponse.Sent send(Long userId) {
		UserMember user = userMemberService.getEmailVerificationTarget(userId);
		String email = user.getEmail();

		Issued issued = codeService.issue(userId);
		try {
			sendExecutor.execute(() -> deliver(email, issued));
		} catch (RejectedExecutionException e) {
			// 접수하지 못했으니 사용자가 바로 다시 시도할 수 있게 번호·횟수·간격을 모두 되돌린다
			codeService.cancel(issued);
			countSend("rejected");
			throw new BusinessException(AuthExceptionCode.EMAIL_SEND_QUEUE_FULL);
		}
		return EmailVerificationResponse.Sent.builder()
			.expiresAt(issued.expiresAt())
			.resendAvailableAt(issued.resendAvailableAt())
			.build();
	}

	/**
	 * 인증번호를 확인하고 인증 시각을 남긴다.
	 * 확인 순서: 회원(404) → 정지(401) → 이미 인증(409) → 인증번호(400).
	 * 번호를 지운 뒤 DB 반영이 실패하면 사용자는 번호를 다시 받아야 한다. (드문 경우라 받아들인다)
	 */
	public EmailVerificationResponse.Confirmed confirm(Long userId, EmailVerificationRequest.Confirm request) {
		userMemberService.getEmailVerificationTarget(userId);
		codeService.confirm(userId, request.code());

		LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
		userMemberService.markEmailVerified(userId, now);
		return EmailVerificationResponse.Confirmed.builder().emailVerifiedAt(now).build();
	}

	/** 발송 스레드에서 실행된다. 실패해도 예외를 밖으로 내보내지 않고 인증번호·횟수를 되돌린다. */
	private void deliver(String email, Issued issued) {
		try {
			emailSender.sendVerificationCode(email, issued.code(), properties.codeTtl());
			countSend("success");
		} catch (RuntimeException e) {
			countSend("failure");
			// 메일 라이브러리의 예외 메시지에는 받는 주소가 들어 있을 수 있어서 종류만 남긴다. (로그에 개인정보를 남기지 않는다)
			log.warn("이메일 인증번호 발송에 실패했습니다. userId={}, error={}", issued.userId(), e.getClass().getSimpleName());
			discardQuietly(issued);
		}
	}

	private void discardQuietly(Issued issued) {
		try {
			codeService.discardAfterSendFailure(issued);
		} catch (RuntimeException e) {
			log.error("발송 실패한 인증번호를 되돌리지 못했습니다. userId={}, error={}", issued.userId(),
				e.getClass().getSimpleName());
		}
	}

	private void countSend(String result) {
		meterRegistry.counter(SEND_METRIC, "result", result).increment();
	}
}
