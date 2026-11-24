package com.squirrel.tickting.domain.booking.facade;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예매 설정값. 값은 application.yaml 의 app.booking 에 있다.
 *
 * @param lockWait         좌석·사용자 락을 기다리는 최대 시간. 길수록 인기 좌석에 요청이 쌓여 서버 스레드가 모자라서 짧게 둔다
 * @param holdDuration     좌석을 잡고 결제를 기다려 주는 시간. 예매 만료 시각 = 점유 시각 + 이 값 (임시 정책)
 * @param expiryBatchSize  만료 정리를 한 번의 트랜잭션에서 처리하는 예매 수. 클수록 호출이 적지만 잠금이 오래 이어진다
 */
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(Duration lockWait, Duration holdDuration, int expiryBatchSize) {
}
