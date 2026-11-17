package com.squirrel.tickting.domain.booking.scheduler;

import com.squirrel.tickting.domain.booking.facade.BookingExpiryFacade;
import com.squirrel.tickting.domain.booking.facade.BookingProperties;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 점유를 주기적으로 정리한다. (doc/part5-operations-area.md 5-2 ①)
 * 서버가 여러 대면 모두 이 작업을 돌리지만, DB 의 SKIP LOCKED 가 같은 예매를 두 번 처리하지 않게 한다.
 * 주기는 app.booking.expiry-interval 설정값이다. 짧을수록 풀린 좌석이 빨리 다시 보이고, 길수록 DB 부담이 적다.
 * 이전 실행이 끝난 뒤부터 간격을 재서(fixedDelay) 정리가 길어져도 실행이 겹치지 않는다.
 *
 * 테스트에서는 app.booking.expiry-enabled=false 로 꺼서, 테스트가 만든 데이터를 이 작업이 먼저 처리하지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.booking", name = "expiry-enabled", havingValue = "true")
public class BookingExpiryScheduler {

	private final BookingExpiryFacade bookingExpiryFacade;
	private final BookingProperties properties;

	@Scheduled(fixedDelayString = "${app.booking.expiry-interval}")
	public void expireOverdue() {
		try {
			int released = bookingExpiryFacade.expireOverdue(LocalDateTime.now(), properties.expiryBatchSize());
			if (released > 0) {
				log.info("만료된 점유 좌석 {}개를 되돌렸다.", released);
			}
		} catch (RuntimeException e) {
			// 실패해도 다음 주기에 다시 시도한다. 예외를 밖으로 던져도 스케줄은 이어지지만, 어떤 작업이었는지 남기려고 직접 기록한다.
			log.error("만료된 점유 정리에 실패했다. 다음 주기에 다시 시도한다.", e);
		}
	}
}
