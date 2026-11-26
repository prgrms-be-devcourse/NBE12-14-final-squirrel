package com.squirrel.tickting.domain.booking.scheduler;

import com.squirrel.tickting.domain.booking.facade.BookingCancelFacade;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 취소한 회차의 예매를 주기적으로 처리한다. (doc/part5-operations-area.md 5-2 ④)
 * 환불을 만드는 일(①)과 끝난 환불을 예매에 반영하는 일(②)을 차례로 한다. 사이에서 토스에 보내는 일은 환불 스케줄러가 맡아서,
 * 이 작업이나 환불 스케줄러를 끄면 회차 취소의 환불이 멈춘다. 한쪽이 실패해도 다른 쪽은 돈다.
 * 서버가 여러 대면 모두 이 작업을 돌리지만 예매 락, 예매 상태 조건, 환불의 UNIQUE 인덱스가 같은 예매를 두 번 처리하지 않게 한다.
 * 이전 실행이 끝난 뒤부터 간격을 재서(fixedDelay) 대상이 많아 실행이 길어져도 겹치지 않는다.
 *
 * 테스트에서는 app.booking.schedule-cancel.enabled=false 로 꺼서, 테스트가 만든 데이터를 이 작업이 먼저 처리하지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.booking.schedule-cancel", name = "enabled", havingValue = "true")
public class ScheduleCancelScheduler {

	private final BookingCancelFacade bookingCancelFacade;

	@Scheduled(fixedDelayString = "${app.booking.schedule-cancel.interval}")
	public void processCanceledSchedules() {
		try {
			int requested = bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now());
			if (requested > 0) {
				log.info("취소한 회차의 환불 {}건을 만들었다.", requested);
			}
		} catch (RuntimeException e) {
			log.error("회차 취소 환불을 만드는 작업에 실패했다. 다음 주기에 다시 시도한다.", e);
		}
		try {
			int applied = bookingCancelFacade.applyScheduleCancels(LocalDateTime.now());
			if (applied > 0) {
				log.info("환불이 끝난 예매 {}건을 취소로 바꿨다.", applied);
			}
		} catch (RuntimeException e) {
			log.error("회차 취소 환불을 예매에 반영하는 작업에 실패했다. 다음 주기에 다시 시도한다.", e);
		}
	}
}
