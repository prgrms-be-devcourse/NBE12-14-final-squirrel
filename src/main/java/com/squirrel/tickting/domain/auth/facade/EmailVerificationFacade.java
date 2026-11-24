package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.auth.dto.EmailVerificationRequest;
import com.squirrel.tickting.domain.auth.dto.EmailVerificationResponse;
import com.squirrel.tickting.domain.auth.mail.EmailSendExecutor;
import com.squirrel.tickting.domain.auth.mail.EmailSender;
import com.squirrel.tickting.domain.auth.service.EmailVerificationCodeService;
import com.squirrel.tickting.domain.auth.service.EmailVerificationCodeService.Issued;
import com.squirrel.tickting.domain.auth.service.EmailVerificationProperties;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.util.EmailUtil;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 가입 전 이메일 인증. 이미 가입한 이메일인지(user·seller 도메인)와 인증번호·메일(auth 도메인)을 엮는다.
 * 가입 전이라 로그인이 없고, 인증을 마친 이메일은 Redis 에 표시로 남아 가입 API 가 확인한다.
 * 예매자와 판매자가 같이 쓰되 계정 종류(AccountType)를 받아서, 인증 상태와 한도를 종류별로 따로 둔다.
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
	private final SellerMemberService sellerMemberService;
	private final EmailVerificationCodeService codeService;
	private final EmailSender emailSender;
	private final EmailSendExecutor sendExecutor;
	private final EmailVerificationProperties properties;
	private final MeterRegistry meterRegistry;

	/**
	 * 인증번호를 만들어 저장하고 발송을 접수한다.
	 * 확인 순서: 이미 가입한 이메일(409) → 재발송 간격(429) → 하루 한도(429) → 발송 대기(503).
	 * 가입한 이메일에는 번호도 간격·횟수 키도 만들지 않고 메일도 보내지 않는다. (남의 메일함에 메일을 보내는 길을 줄이려는 것이다)
	 */
	public EmailVerificationResponse.Sent send(AccountType type, EmailVerificationRequest.Send request) {
		String email = EmailUtil.normalize(request.email());
		requireEmailNotTaken(type, email);

		Issued issued = codeService.issue(type, email);
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
	 * 인증번호를 확인하고 그 이메일을 인증한 것으로 표시한다. 확인 순서: 인증번호(400).
	 * 이미 가입한 이메일인지는 보지 않는다. 가입한 이메일로는 번호를 받을 수 없어서 확인할 번호가 없기 때문이다.
	 */
	public EmailVerificationResponse.Confirmed confirm(AccountType type, EmailVerificationRequest.Confirm request) {
		String email = EmailUtil.normalize(request.email());
		LocalDateTime verifiedUntil = codeService.confirm(type, email, request.code());
		return EmailVerificationResponse.Confirmed.builder().verifiedUntil(verifiedUntil).build();
	}

	/**
	 * 그 계정 종류의 테이블에 이미 가입한 이메일이면 막는다. 예매자와 판매자는 테이블이 달라서,
	 * 예매자로 가입한 이메일도 판매자 가입에는 쓸 수 있다. (그 반대도 같다)
	 */
	private void requireEmailNotTaken(AccountType type, String email) {
		switch (type) {
			case USER -> userMemberService.requireEmailNotTaken(email);
			case SELLER -> sellerMemberService.requireEmailNotTaken(email);
			case ADMIN -> throw new IllegalArgumentException("관리자는 가입이 없어서 이메일 인증을 쓰지 않습니다.");
		}
	}

	/** 발송 스레드에서 실행된다. 실패해도 예외를 밖으로 내보내지 않고 인증번호·횟수를 되돌린다. */
	private void deliver(String email, Issued issued) {
		try {
			emailSender.sendVerificationCode(email, issued.code(), properties.codeTtl());
			countSend("success");
		} catch (RuntimeException e) {
			countSend("failure");
			// 메일 라이브러리의 예외 메시지에는 받는 주소가 들어 있을 수 있어서 종류만 남긴다. (로그에 개인정보를 남기지 않는다)
			log.warn("이메일 인증번호 발송에 실패했습니다. error={}", e.getClass().getSimpleName());
			discardQuietly(issued);
		}
	}

	private void discardQuietly(Issued issued) {
		try {
			codeService.discardAfterSendFailure(issued);
		} catch (RuntimeException e) {
			log.error("발송 실패한 인증번호를 되돌리지 못했습니다. error={}", e.getClass().getSimpleName());
		}
	}

	private void countSend(String result) {
		meterRegistry.counter(SEND_METRIC, "result", result).increment();
	}
}
