package com.squirrel.tickting.domain.performance.scheduler;

import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 판매 오픈이 가까운 공개 공연의 판매 좌석을 주기적으로 만든다. (doc/part3-performance-area.md 3-5, doc/part5-operations-area.md 5-3)
 * 서버가 여러 대면 모두 이 작업을 돌리지만, 공연 락·상태 조건부 UPDATE·UNIQUE 가 같은 회차의 좌석이 두 번 만들어지지 않게 한다.
 * 이전 실행이 끝난 뒤부터 간격을 재서(fixedDelay) 만들 회차가 많아 길어져도 실행이 겹치지 않는다.
 * 첫 실행을 늦추지 않아 서버가 켜지면 바로 한 번 돈다. 서버가 꺼져 있던 동안 만들 때가 된 회차를 한 주기 더 기다리게 하지 않으려는 것이다.
 *
 * 테스트에서는 app.performance.seat-generation.enabled=false 로 꺼서, 테스트가 만든 회차를 이 작업이 먼저 처리하지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.performance.seat-generation", name = "enabled", havingValue = "true")
public class SeatGenerationScheduler {

	private final SellerPerformanceService sellerPerformanceService;

	@Scheduled(fixedDelayString = "${app.performance.seat-generation.interval}")
	public void generateDueSeats() {
		try {
			sellerPerformanceService.generateDueSeats();
		} catch (RuntimeException e) {
			// 회차별 실패는 서비스가 처리하므로 여기 오는 것은 대상 조회 실패다. 다음 주기에 다시 시도한다.
			log.error("판매 좌석 자동 생성 대상을 처리하지 못했다. 다음 주기에 다시 시도한다.", e);
		}
	}
}
