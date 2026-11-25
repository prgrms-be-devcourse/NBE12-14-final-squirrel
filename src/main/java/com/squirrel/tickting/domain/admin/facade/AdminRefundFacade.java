package com.squirrel.tickting.domain.admin.facade;

import com.squirrel.tickting.domain.admin.dto.AdminRefundResponse;
import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.domain.audit.type.ActorType;
import com.squirrel.tickting.domain.payment.facade.RefundRetryProperties;
import com.squirrel.tickting.domain.payment.facade.RefundSender;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.service.RefundService;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 환불 재처리(ADM-06). 환불(payment)과 감사 로그(audit) 두 도메인을 엮는다. (doc/part5-operations-area.md 5-2 ③ 4단계)
 * 재시도 스케줄러와 같은 세 단계이고, 마지막 단계에서 결과 기록과 감사 로그를 한 트랜잭션으로 묶는다.
 *   1. 가져오기 (트랜잭션): 실패한 환불을 요청 중으로 바꾼다. 스케줄러와 겹치면 한쪽만 가져가고 나머지는 409 다.
 *   2. 보내기 (트랜잭션 밖): 처음과 같은 멱등키·금액·사유로 토스에 보낸다.
 *   3. 기록 (트랜잭션): DONE/FAILED 와 감사 로그를 함께 남긴다. 토스 호출은 되돌릴 수 없어서, 함께 묶는 것은 기록과 로그뿐이다.
 *
 * 3단계가 실패하면 환불은 요청 중으로 남고 관리자는 오류를 받는다. 재시도 스케줄러가 5분 뒤 같은 멱등키로 다시 보내 확정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminRefundFacade {

	private static final String TARGET_TYPE = "REFUND";

	private final RefundService refundService;
	private final RefundSender refundSender;
	private final RefundRetryProperties retryProperties;
	private final AuditLogService auditLogService;
	private final TransactionTemplate transactionTemplate;

	/** 감사 로그의 전후 값. 완료되면 실패 사유가 비어서 null 을 담을 수 있게 record 로 둔다. */
	private record Snapshot(RefundStatus status, Integer retryCount, String failReason) {
	}

	public AdminRefundResponse.Retried retry(Long adminId, Long refundId) {
		Refund claimed = transactionTemplate.execute(status -> refundService.claimForManualRetry(
			refundId, LocalDateTime.now(), retryProperties.idempotencyKeyValidity()));
		// 가져오기에서 바뀌는 것은 상태와 횟수뿐이라, 바뀌기 전 값은 가져온 환불에서 되짚는다.
		Snapshot before = new Snapshot(RefundStatus.FAILED, claimed.getRetryCount() - 1, claimed.getFailReason());

		String failReason = refundSender.send(claimed);
		if (failReason != null) {
			log.warn("관리자 환불 재처리가 실패했다. 환불 번호: {}", refundId);
		}

		Refund result = transactionTemplate.execute(status -> {
			Refund recorded = failReason == null
				? refundService.complete(refundId, LocalDateTime.now())
				: refundService.fail(refundId, failReason);
			auditLogService.saveHistory(AuditLogCommand.builder()
				.actorType(ActorType.ADMIN)
				.actorId(adminId)
				.targetType(TARGET_TYPE)
				.targetId(refundId)
				.action("RETRY")
				.before(before)
				.after(new Snapshot(recorded.getStatus(), recorded.getRetryCount(), recorded.getFailReason()))
				.build());
			return recorded;
		});
		return AdminRefundResponse.Retried.from(result);
	}
}
