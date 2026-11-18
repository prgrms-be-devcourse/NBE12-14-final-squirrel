package com.squirrel.tickting.domain.payment.gateway;

import java.time.LocalDateTime;

/**
 * 승인된 결제. 대행사의 응답 중 우리가 저장하는 값만 담는다.
 *
 * @param method     결제 수단 (카드, 간편결제 등)
 * @param approvedAt 승인 시각. 서버의 시간대로 바꾼 값이다
 */
public record GatewayPayment(String paymentKey, String method, LocalDateTime approvedAt) {
}
