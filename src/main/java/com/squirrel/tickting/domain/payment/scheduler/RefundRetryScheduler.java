package com.squirrel.tickting.domain.payment.scheduler;

import com.squirrel.tickting.domain.payment.facade.RefundRetryFacade;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 실패했거나 결과를 모르는 환불을 주기적으로 다시 보낸다. (doc/part5-operations-area.md 5-3 환불 재시도)
 * 서버가 여러 대면 모두 이 작업을 돌리지만, 같은 환불은 한 서버만 가져간다. (RefundRetryFacade 참고)
 * 이전 실행이 끝난 뒤부터 간격을 재서(fixedDelay) 토스 응답이 느려 실행이 길어져도 겹치지 않는다.
 *
 * 테스트에서는 app.payment.refund-retry.enabled=false 로 꺼서, 테스트가 만든 환불을 이 작업이 먼저 보내지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.payment.refund-retry", name = "enabled", havingValue = "true")
public class RefundRetryScheduler {

	private final RefundRetryFacade refundRetryFacade;

	@Scheduled(fixedDelayString = "${app.payment.refund-retry.interval}")
	public void retryDue() {
		try {
			int retried = refundRetryFacade.retryDue(LocalDateTime.now());
			if (retried > 0) {
				log.info("환불 {}건을 다시 보냈다.", retried);
			}
		} catch (RuntimeException e) {
			log.error("환불 재시도 작업에 실패했다. 다음 주기에 다시 시도한다.", e);
		}
	}
}
