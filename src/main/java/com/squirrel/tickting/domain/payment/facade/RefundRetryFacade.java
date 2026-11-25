package com.squirrel.tickting.domain.payment.facade;

import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.service.RefundService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실패했거나 결과를 모르는 환불을 같은 멱등키로 토스에 다시 보낸다. (doc/part5-operations-area.md 5-2 ③)
 * 결제 도메인 하나의 일이지만 토스 호출을 DB 트랜잭션 밖에 두려고 트랜잭션을 나눠야 해서 파사드가 맡는다.
 *   1. 가져오기 (트랜잭션): 조건부 UPDATE 로 환불을 요청 중(PROCESSING)으로 바꾸고, 시도 횟수와 마지막 시도 시각을 올린다.
 *   2. 보내기 (트랜잭션 밖): 처음과 같은 멱등키·금액·사유로 토스에 환불을 요청한다.
 *   3. 반영 (트랜잭션): 완료(DONE) 또는 실패(FAILED)로 기록한다.
 *
 * 대상은 마지막 시도 후 delay 가 지난 것만이다.
 *   - 실패(FAILED): maxCount 회 미만일 때만 다시 보낸다. 넘으면 관리자가 처리한다. (ADM-05·06)
 *   - 요청 중(PROCESSING): 보내는 도중 서버가 죽었거나 결과를 기록하지 못한 건이다. 돈이 나갔는지 모르는 상태라
 *     횟수와 상관없이 다시 보내서 확정한다. 같은 멱등키라 이미 환불됐어도 토스가 두 번 돌려주지 않는다.
 *
 * 서버 여러 대가 같은 환불을 가져가는 일은 1단계의 조건부 UPDATE 가 막는다. 먼저 바꾼 서버가 마지막 시도 시각을 지금으로 올려서
 * 나머지 서버의 UPDATE 는 0행이 된다. 분산락을 쓰지 않는 이유는 환불 행 하나만 바뀌어서 좌석처럼 여러 행을 함께 지킬 일이 없어서다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RefundRetryProperties.class)
public class RefundRetryFacade {

	private final TransactionTemplate transactionTemplate;
	private final RefundService refundService;
	private final RefundSender refundSender;
	private final RefundRetryProperties properties;

	/**
	 * 다시 보낼 때가 된 환불을 batchSize 건까지 하나씩 차례로 보낸다. 한 건이 예외로 끝나도 나머지는 이어서 보낸다.
	 *
	 * @return 이 서버가 가져가서 토스에 보낸 환불 수
	 */
	public int retryDue(LocalDateTime now) {
		LocalDateTime cutoff = now.minus(properties.delay());
		List<Long> refundIds = refundService.findRetryDue(cutoff, properties.maxCount(), properties.batchSize());
		int retried = 0;
		for (Long refundId : refundIds) {
			try {
				if (retry(refundId, now, cutoff)) {
					retried++;
				}
			} catch (RuntimeException e) {
				// 결과를 기록하지 못해도 환불은 요청 중으로 남아서 delay 뒤에 다시 확정한다.
				log.error("환불 재시도에 실패했다. 다음 주기에 다시 시도한다. 환불 번호: {}", refundId, e);
			}
		}
		return retried;
	}

	private boolean retry(Long refundId, LocalDateTime now, LocalDateTime cutoff) {
		Optional<Refund> claimed = transactionTemplate.execute(
			status -> refundService.claimForRetry(refundId, now, cutoff, properties.maxCount()));
		if (claimed == null || claimed.isEmpty()) {
			return false;
		}
		Refund refund = claimed.get();
		String failReason = refundSender.send(refund);
		transactionTemplate.executeWithoutResult(status -> {
			if (failReason == null) {
				refundService.complete(refundId, LocalDateTime.now());
			} else {
				refundService.fail(refundId, failReason);
			}
		});
		if (failReason != null) {
			log.warn("환불 재시도가 실패했다. 환불 번호: {}, 시도 횟수: {}", refundId, refund.getRetryCount());
		}
		return true;
	}
}
