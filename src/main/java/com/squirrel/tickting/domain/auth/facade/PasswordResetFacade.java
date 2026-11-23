package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.auth.dto.PasswordResetRequest;
import com.squirrel.tickting.domain.auth.dto.PasswordResetResponse;
import com.squirrel.tickting.domain.auth.mail.EmailSendExecutor;
import com.squirrel.tickting.domain.auth.mail.EmailSender;
import com.squirrel.tickting.domain.auth.service.PasswordResetProperties;
import com.squirrel.tickting.domain.auth.service.PasswordResetTokenService;
import com.squirrel.tickting.domain.auth.service.PasswordResetTokenService.Issued;
import com.squirrel.tickting.domain.auth.service.PasswordResetTokenService.Target;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.util.EmailUtil;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 찾기. 계정(user·seller 도메인)과 재설정 링크·메일(auth 도메인)을 엮는다.
 *
 * <p>발송은 이메일 인증처럼 비동기다. 접수하면 바로 응답하고 메일은 발송 스레드가 뒤에서 보낸다. (스레드는 이메일 인증과 같이 쓴다)
 * 메일을 보내는 동안 DB 트랜잭션을 쥐지 않는다. (파사드는 트랜잭션을 열지 않고, 서비스가 각자 짧게 연다)
 * 세션을 끊는 일은 하지 않는다. (다른 기기 세션 끊기는 세션 저장 방식을 바꾸는 별도 PR 에서 맡는다. doc/seller-progress.md 의 후속 TODO 참고)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetFacade {

	private static final String SEND_METRIC = "email.password-reset.send";

	private final UserMemberService userMemberService;
	private final SellerMemberService sellerMemberService;
	private final PasswordResetTokenService tokenService;
	private final EmailSender emailSender;
	private final EmailSendExecutor sendExecutor;
	private final PasswordResetProperties properties;
	private final MeterRegistry meterRegistry;
	private final Clock clock;

	/**
	 * 예매자 재설정 메일을 요청한다. 확인 순서: 이메일 가입 여부(404) → 재요청 간격(429) → 하루 한도(429) → 발송 대기(503).
	 * 가입되지 않은 이메일이면 간격·하루 키를 만들지 않는다.
	 */
	public PasswordResetResponse.Sent requestForUser(PasswordResetRequest.Mail request) {
		Long userId = userMemberService.getPasswordResetTarget(request.email()).getId();
		return request(AccountType.USER, userId, request.email());
	}

	/** 판매자(대표·담당자) 재설정 메일을 요청한다. 확인 순서는 예매자와 같다. */
	public PasswordResetResponse.Sent requestForSeller(PasswordResetRequest.Mail request) {
		Long memberId = sellerMemberService.getPasswordResetTarget(request.email()).getId();
		return request(AccountType.SELLER, memberId, request.email());
	}

	/** 링크가 유효한지만 본다. 토큰을 소모하지 않는다. */
	public PasswordResetResponse.Validity validate(PasswordResetRequest.Validate request) {
		Optional<Target> target = tokenService.peek(request.token());
		return PasswordResetResponse.Validity.builder()
			.valid(target.isPresent())
			.accountType(target.map(Target::type).orElse(null))
			.build();
	}

	/**
	 * 새 비밀번호를 정한다. 확인 순서: 비밀번호 확인(400) → 링크(400).
	 * 링크를 먼저 쓰고(같은 링크로 동시에 요청해도 하나만 성공) 비밀번호를 바꾼 뒤, 그 계정의 다른 링크를 모두 폐기한다.
	 * 링크를 쓴 뒤 비밀번호 변경이 실패하면 사용자는 링크를 다시 받아야 한다. (드문 경우라 받아들인다)
	 */
	public void confirm(PasswordResetRequest.Confirm request) {
		// Redis 를 보기 전에 끝낼 수 있는 검사라 링크 확인보다 먼저 한다
		if (!request.password().equals(request.passwordConfirm())) {
			throw new BusinessException(AuthExceptionCode.PASSWORD_CONFIRM_MISMATCH);
		}
		Target target = tokenService.consume(request.token());

		LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
		String email = switch (target.type()) {
			case USER -> userMemberService.resetPassword(target.id(), request.password(), now);
			case SELLER -> sellerMemberService.resetPassword(target.id(), request.password(), now);
			case ADMIN -> throw new BusinessException(AuthExceptionCode.PASSWORD_RESET_TOKEN_INVALID);
		};

		revokeAllQuietly(target);
		notifyChanged(email, target.type());
	}

	private PasswordResetResponse.Sent request(AccountType type, Long memberId, String rawEmail) {
		String email = EmailUtil.normalize(rawEmail);
		Issued issued = tokenService.issue(type, memberId, email);
		String link = link(issued.token());
		try {
			sendExecutor.execute(() -> deliver(email, link, issued));
		} catch (RejectedExecutionException e) {
			// 접수하지 못했으니 사용자가 바로 다시 시도할 수 있게 링크·횟수·간격을 모두 되돌린다
			tokenService.cancel(issued);
			countSend("rejected");
			throw new BusinessException(AuthExceptionCode.EMAIL_SEND_QUEUE_FULL);
		}
		return PasswordResetResponse.Sent.builder()
			.email(rawEmail)
			.expiresAt(issued.expiresAt())
			.resendAvailableAt(issued.resendAvailableAt())
			.build();
	}

	/** 링크는 # 뒤에 토큰을 둔다. 브라우저가 # 뒷부분을 서버로 보내지 않아서 Nginx 접근 로그에 토큰이 남지 않는다. */
	private String link(String token) {
		String base = properties.frontendBaseUrl();
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + "/reset-password#token=" + token;
	}

	/** 발송 스레드에서 실행된다. 실패해도 예외를 밖으로 내보내지 않고 링크·횟수를 되돌린다. */
	private void deliver(String email, String link, Issued issued) {
		try {
			emailSender.sendPasswordReset(email, issued.type(), link, properties.linkTtl());
			countSend("success");
		} catch (RuntimeException e) {
			countSend("failure");
			// 메일 라이브러리의 예외 메시지에는 받는 주소가 들어 있을 수 있어서 종류만 남긴다. (로그에 개인정보를 남기지 않는다)
			log.warn("비밀번호 재설정 메일 발송에 실패했습니다. type={}, memberId={}, error={}", issued.type(),
				issued.memberId(), e.getClass().getSimpleName());
			discardQuietly(issued);
		}
	}

	private void discardQuietly(Issued issued) {
		try {
			tokenService.discardAfterSendFailure(issued);
		} catch (RuntimeException e) {
			log.error("발송 실패한 재설정 링크를 되돌리지 못했습니다. type={}, memberId={}, error={}", issued.type(),
				issued.memberId(), e.getClass().getSimpleName());
		}
	}

	/** 비밀번호는 이미 바뀌었으므로 폐기에 실패해도 응답은 성공이다. 다른 링크가 최대 링크 유효 시간까지 남는다. */
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
