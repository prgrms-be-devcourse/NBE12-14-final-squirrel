package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.auth.mail.EmailSendExecutor;
import com.squirrel.tickting.domain.auth.mail.EmailSender;
import com.squirrel.tickting.domain.auth.service.PasswordResetTokenService;
import com.squirrel.tickting.domain.auth.service.PasswordResetTokenService.Target;
import com.squirrel.tickting.global.security.AccountType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 비밀번호가 바뀐 뒤의 뒷정리: 그 계정의 재설정 링크를 모두 폐기하고 변경 알림 메일을 보낸다.
 * 재설정(PasswordResetFacade)과 변경(PasswordChangeFacade)이 같이 쓴다. 비밀번호는 이미 바뀌었으므로 어느 쪽이 실패해도 예외를 내보내지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class PasswordChangedNotifier {

	/** 재설정 메일과 변경 알림 메일의 발송 지표. result 태그로 나눈다. */
	static final String SEND_METRIC = "email.password-reset.send";

	private final PasswordResetTokenService tokenService;
	private final EmailSender emailSender;
	private final EmailSendExecutor sendExecutor;
	private final MeterRegistry meterRegistry;

	void passwordChanged(Target target, String email) {
		revokeAllQuietly(target);
		notifyChanged(email, target.type());
	}

	/** 폐기에 실패해도 응답은 성공이다. 다른 링크가 최대 링크 유효 시간까지 남는다. */
	private void revokeAllQuietly(Target target) {
		try {
			tokenService.revokeAll(target.type(), target.id());
		} catch (RuntimeException e) {
			log.error("비밀번호를 바꾼 계정의 다른 재설정 링크를 폐기하지 못했습니다. type={}, memberId={}, error={}", target.type(),
				target.id(), e.getClass().getSimpleName());
		}
	}

	/** 변경 알림은 보조 기능이라 접수나 발송에 실패해도 비밀번호 변경은 성공으로 둔다. */
	private void notifyChanged(String email, AccountType type) {
		try {
			sendExecutor.execute(() -> {
				try {
					emailSender.sendPasswordChanged(email, type);
					countSend("changed-success");
				} catch (RuntimeException e) {
					countSend("changed-failure");
					log.warn("비밀번호 변경 알림 메일 발송에 실패했습니다. type={}, error={}", type, e.getClass().getSimpleName());
				}
			});
		} catch (RejectedExecutionException e) {
			countSend("changed-rejected");
			log.warn("발송 대기가 가득 차서 비밀번호 변경 알림 메일을 접수하지 못했습니다. type={}", type);
		}
	}

	private void countSend(String result) {
		meterRegistry.counter(SEND_METRIC, "result", result).increment();
	}
}
