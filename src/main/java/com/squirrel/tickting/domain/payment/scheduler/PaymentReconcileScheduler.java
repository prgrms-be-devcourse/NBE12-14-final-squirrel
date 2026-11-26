package com.squirrel.tickting.domain.payment.scheduler;

import com.squirrel.tickting.domain.payment.facade.PaymentReconcileFacade;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승인 결과를 모르는 채 남은 결제를 주기적으로 토스에 조회해서 확정한다. (doc/part5-operations-area.md 5-2 ② 결제 불일치 보정)
 * 서버가 여러 대면 모두 이 작업을 돌리지만, 결제를 닫는 쪽은 예매 단위 락 안에서 결제 상태를 다시 확인해서 한 서버만 처리한다.
 * (PaymentReconcileFacade 참고) 이전 실행이 끝난 뒤부터 간격을 재서(fixedDelay) 토스 응답이 느려 실행이 길어져도 겹치지 않는다.
 *
 * 테스트에서는 app.payment.reconcile.enabled=false 로 꺼서, 테스트가 만든 결제를 이 작업이 먼저 닫지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.payment.reconcile", name = "enabled", havingValue = "true")
public class PaymentReconcileScheduler {

	private final PaymentReconcileFacade paymentReconcileFacade;

	@Scheduled(fixedDelayString = "${app.payment.reconcile.interval}")
	public void reconcileDue() {
		try {
			int reconciled = paymentReconcileFacade.reconcileDue(LocalDateTime.now());
			if (reconciled > 0) {
				log.info("결제 {}건을 토스에 조회해서 확정했다.", reconciled);
			}
		} catch (RuntimeException e) {
			log.error("결제 보정 작업에 실패했다. 다음 주기에 다시 시도한다.", e);
		}
	}
}
