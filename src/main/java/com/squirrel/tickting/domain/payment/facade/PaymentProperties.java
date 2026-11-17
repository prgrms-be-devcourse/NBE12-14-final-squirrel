package com.squirrel.tickting.domain.payment.facade;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 설정값. 값은 application.yaml 의 app.payment 에 있다.
 *
 * @param lockWait 예매 단위 락을 기다리는 최대 시간. 같은 예매에 결제 요청이 겹칠 때만 기다리므로 짧게 둔다. 넘으면 COMMON004
 */
@ConfigurationProperties(prefix = "app.payment")
public record PaymentProperties(Duration lockWait) {
}
