package com.squirrel.tickting.domain.booking.facade;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예매 설정값. 값은 application.yaml 의 app.booking 에 있다.
 *
 * @param lockWait         좌석·사용자 락을 기다리는 최대 시간. 길수록 인기 좌석에 요청이 쌓여 서버 스레드가 모자라서 짧게 둔다
 * @param holdDuration     좌석을 잡고 결제를 기다려 주는 시간. 예매 만료 시각 = 점유 시각 + 이 값 (임시 정책)
 * @param expiryBatchSize  만료 정리를 한 번의 트랜잭션에서 처리하는 예매 수. 클수록 호출이 적지만 잠금이 오래 이어진다
 * @param scheduleCancel   취소한 회차의 환불을 만들고 반영하는 작업의 설정
 */
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(Duration lockWait, Duration holdDuration, int expiryBatchSize,
	ScheduleCancel scheduleCancel) {

	/**
	 * 켜고 끄는 값(enabled)과 주기(interval)는 스케줄러가 설정에서 바로 읽는다.
	 *
	 * @param batchSize 대상 예매를 한 번에 가져오는 수. 한 번 실행에서 이 수씩 끊어 모두 처리한다 (임시값)
	 */
	public record ScheduleCancel(int batchSize) {
	}
}
