package com.squirrel.tickting.domain.payment.facade;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 불일치 보정 설정값. 값은 application.yaml 의 app.payment.reconcile 에 있다. (주기와 켜고 끄기는 스케줄러가 읽는다)
 * 모든 값은 임시 정책이다. (doc/part5-operations-area.md 5-2 ②는 "오래된 건"이라고만 정했다)
 *
 * @param readyAfter      결제를 준비한 지 이 시간이 지난 준비(READY) 건부터 조회한다. 예매 점유(7분)와 승인 때 늘리는 시간(2분),
 *                        만료 정리 주기(1분)가 모두 지난 뒤여야 결제창을 열어 두고 결제 중인 사용자의 예매를 건드리지 않는다
 * @param processingAfter 승인을 시작한 지 이 시간이 지난 승인 중(PROCESSING) 건부터 조회한다. 토스 응답 제한(10초)보다 충분히 길어야
 *                        지금 응답을 기다리는 승인을 건드리지 않는다
 * @param giveUpAfter     토스가 "아직 끝나지 않음"으로 답하는 결제를 이 시간이 지나면 결제되지 않은 것으로 닫는다.
 *                        토스는 결제 요청 뒤 10분 안에 승인하지 않으면 결제 정보를 지우고, 우리는 승인을 다시 보내지 않아서 그 뒤에는 결제될 수 없다
 * @param batchSize       한 번에 조회하는 결제 수. 토스에 하나씩 차례로 조회하고, 남은 건은 다음 주기에 이어서 한다
 */
@ConfigurationProperties(prefix = "app.payment.reconcile")
public record PaymentReconcileProperties(Duration readyAfter, Duration processingAfter, Duration giveUpAfter,
										 int batchSize) {
}
