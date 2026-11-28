package com.squirrel.tickting.domain.booking.facade;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예매 설정값. 값은 application.yaml 의 app.booking 에 있다.
 *
 * @param lockWait         좌석·사용자 락을 기다리는 최대 시간. 길수록 인기 좌석에 요청이 쌓여 서버 스레드가 모자라서 짧게 둔다
 * @param holdDuration     좌석을 잡고 결제를 기다려 주는 시간. 예매 만료 시각 = 점유 시각 + 이 값 (임시 정책)
 * @param expiryBatchSize  만료 정리를 한 번의 트랜잭션에서 처리하는 예매 수. 클수록 호출이 적지만 잠금이 오래 이어진다
 * @param delivery         발송 처리 설정
 */
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(Duration lockWait, Duration holdDuration, int expiryBatchSize, Delivery delivery) {

	/**
	 * @param carriers    판매자가 고를 수 있는 택배사. 여기 있는 코드만 저장해서 예매자의 배송 조회 연결이 깨지지 않게 한다
	 * @param bulkMaxSize 송장 일괄 입력에서 한 번에 받는 건수
	 */
	public record Delivery(List<Carrier> carriers, int bulkMaxSize) {
	}

	/**
	 * @param code        저장하는 값
	 * @param name        화면에 보일 이름
	 * @param trackingUrl 배송 조회 주소. 끝에 송장번호를 붙여 쓴다
	 */
	public record Carrier(String code, String name, String trackingUrl) {
	}
}
