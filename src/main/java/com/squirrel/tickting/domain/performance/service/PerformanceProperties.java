package com.squirrel.tickting.domain.performance.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 판매자 공연 설정값. 값은 application.yaml 의 app.performance 에 있다.
 *
 * @param lockWait    공연 단위 락(performance:{공연 번호})을 기다리는 최대 시간. 판매자 수정끼리나 판매 좌석 생성과 겹칠 때만
 *                    기다리므로 짧게 둔다. 넘으면 COMMON004
 * @param seatCleanup 끝난 회차 좌석 정리 설정
 */
@ConfigurationProperties(prefix = "app.performance")
public record PerformanceProperties(Duration lockWait, SeatCleanup seatCleanup) {

	/**
	 * @param retention 공연 일시부터 이만큼 지난 회차의 판매 좌석을 지운다. 줄이면 다음 실행에서 그만큼 더 지우고,
	 *                  지운 좌석은 늘려도 돌아오지 않는다
	 */
	public record SeatCleanup(Duration retention) {
	}
}
