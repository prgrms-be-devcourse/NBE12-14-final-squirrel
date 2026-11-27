package com.squirrel.tickting.domain.seller.facade;

import com.squirrel.tickting.domain.auth.mail.EmailSendExecutor;
import com.squirrel.tickting.domain.auth.mail.EmailSender;
import com.squirrel.tickting.domain.seller.dto.SellerInvitationRequest;
import com.squirrel.tickting.domain.seller.dto.SellerInvitationResponse;
import com.squirrel.tickting.domain.seller.service.SellerInvitationService;
import com.squirrel.tickting.domain.seller.service.SellerInvitationService.Issued;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 담당자 초대 보내기. 초대(seller 도메인)와 메일 발송(auth 도메인의 발송기)을 엮는다.
 *
 * <p>발송은 비밀번호 재설정처럼 비동기다. 초대를 저장(커밋)한 뒤 발송을 접수하고 바로 응답한다.
 * 메일을 보내는 동안 DB 트랜잭션을 쥐지 않는다. (파사드는 트랜잭션을 열지 않고, 서비스가 짧게 연다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(SellerInvitationProperties.class)
public class SellerInvitationFacade {

	private final SellerInvitationService sellerInvitationService;
	private final EmailSender emailSender;
	private final EmailSendExecutor sendExecutor;
	private final SellerInvitationProperties properties;
	private final Clock clock;

	/**
	 * 대표가 담당자를 초대한다. 확인 순서: 이미 판매자 계정인 이메일(409) → 발송 대기(503).
	 * 발송을 접수하지 못하면 만든 초대를 무효로 돌려서 대표가 바로 다시 초대할 수 있게 한다.
	 * 접수한 뒤 발송이 실패하면 초대는 그대로 두고 로그만 남긴다. 메일이 오지 않으면 대표가 다시 초대한다. (이전 링크는 무효가 된다)
	 */
	public SellerInvitationResponse.Invited invite(Long businessId, Long inviterId, SellerInvitationRequest.Invite request) {
		LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
		Issued issued = sellerInvitationService.invite(businessId, inviterId, request.email(), now, properties.linkTtl());
		String link = link(issued.token());
		try {
			sendExecutor.execute(() -> deliver(issued, link));
		} catch (RejectedExecutionException e) {
			sellerInvitationService.expire(issued.invitationId(), now);
			throw new BusinessException(AuthExceptionCode.EMAIL_SEND_QUEUE_FULL);
		}
		return new SellerInvitationResponse.Invited(issued.invitationId(), issued.email(), issued.expiresAt());
	}

	/** 링크는 # 뒤에 토큰을 둔다. 브라우저가 # 뒷부분을 서버로 보내지 않아서 Nginx 접근 로그에 토큰이 남지 않는다. */
	private String link(String token) {
		String base = properties.frontendBaseUrl();
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + "/seller/invitation#token=" + token;
	}

	/** 발송 스레드에서 실행된다. 실패해도 예외를 밖으로 내보내지 않는다. */
	private void deliver(Issued issued, String link) {
		try {
			emailSender.sendStaffInvitation(issued.email(), issued.companyName(), link, properties.linkTtl());
		} catch (RuntimeException e) {
			// 메일 라이브러리의 예외 메시지에는 받는 주소가 들어 있을 수 있어서 종류만 남긴다. (로그에 개인정보를 남기지 않는다)
			log.warn("담당자 초대 메일 발송에 실패했습니다. invitationId={}, error={}", issued.invitationId(),
				e.getClass().getSimpleName());
		}
	}
}
