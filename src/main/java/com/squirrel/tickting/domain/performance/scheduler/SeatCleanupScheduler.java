package com.squirrel.tickting.domain.performance.scheduler;

import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 끝난 회차의 판매 좌석을 하루 한 번 지운다. (doc/part3-performance-area.md 3-5, doc/part5-operations-area.md 5-3)
 * schedule_seat 는 회차마다 공연장 좌석 수만큼 행이 생기고 UPDATE 가 가장 많은 테이블이라, 다시 쓰지 않는 끝난 회차의 행이 쌓이면
 * 판매 중인 회차의 청소(autovacuum)와 인덱스까지 무거워진다.
 * 예매가 몰리는 시간을 피해 새벽에 돈다. 서버 시간대와 상관없이 한국 시각으로 맞춘다.
 * 서버가 여러 대면 모두 돌지만, 회차마다 조건부 UPDATE 가 한 서버만 통과시킨다.
 *
 * 테스트에서는 app.performance.seat-cleanup.enabled=false 로 끄고 서비스를 직접 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.performance.seat-cleanup", name = "enabled", havingValue = "true")
public class SeatCleanupScheduler {

	private final ScheduleSeatService scheduleSeatService;

	@Scheduled(cron = "${app.performance.seat-cleanup.cron}", zone = "Asia/Seoul")
	public void purgeEndedSeats() {
		try {
			scheduleSeatService.purgeEndedSeats(LocalDateTime.now());
		} catch (RuntimeException e) {
			// 회차별 실패는 서비스가 처리하므로 여기 오는 것은 대상 조회 실패다. 다음 실행에서 다시 시도한다.
			log.error("끝난 회차 좌석 정리 대상을 처리하지 못했다. 다음 실행에서 다시 시도한다.", e);
		}
	}
}
