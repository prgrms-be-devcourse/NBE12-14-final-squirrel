package com.squirrel.tickting.domain.payment.facade;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 환불 재시도 설정값. 값은 application.yaml 의 app.payment.refund-retry 에 있다. (주기와 켜고 끄기는 스케줄러가 읽는다)
 *
 * @param delay     마지막으로 토스에 보낸 뒤 이만큼 지나야 다시 보낸다. 토스 응답 제한 시간보다 충분히 길어야 응답을 기다리는 중인 환불을 다시 보내지 않는다
 * @param maxCount  실패한 환불을 자동으로 다시 보내는 횟수. 넘으면 관리자가 처리한다 (doc/part5-operations-area.md 5-2 ③)
 * @param batchSize 한 번에 처리하는 환불 수. 토스에 하나씩 차례로 보내고, 남은 건은 다음 주기에 이어서 한다
 */
@ConfigurationProperties(prefix = "app.payment.refund-retry")
public record RefundRetryProperties(Duration delay, int maxCount, int batchSize) {
}
