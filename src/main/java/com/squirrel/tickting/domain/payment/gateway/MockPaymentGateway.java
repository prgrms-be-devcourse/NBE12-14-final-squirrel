package com.squirrel.tickting.domain.payment.gateway;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 결제 승인 Mock. 토스 키 없이 결제 흐름을 만들고 테스트하려고 둔다. 실제 대행사를 부르지 않고 돈도 움직이지 않는다.
 *
 * 결제 키로 결과를 고른다. (결제 키 접두사)
 *   reject_       승인 거절 (카드사 거절)
 *   unavailable_  승인 여부를 알 수 없음 (통신 오류)
 *   slow_         성공하지만 느리게 응답 (동시 요청이 겹치는 상황을 만들 때)
 *   그 밖의 값     성공
 *
 * 누가 승인을 요청했는지와 상관없이 무조건 승인하므로 운영에서는 절대 쓰면 안 된다. (prod 프로필에서는 등록되지 않는다)
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "mock")
public class MockPaymentGateway implements PaymentGateway {

	private static final long SLOW_MILLIS = 300;

	private final List<String> confirmedOrderIds = new CopyOnWriteArrayList<>();

	@Override
	public GatewayPayment confirm(String paymentKey, String orderId, int amount) {
		confirmedOrderIds.add(orderId);
		if (paymentKey.startsWith("reject_")) {
			throw new GatewayRejectedException("REJECT_CARD_COMPANY", "카드사에서 결제를 거절했습니다.");
		}
		if (paymentKey.startsWith("unavailable_")) {
			throw new GatewayUnavailableException("Mock: 결제 대행사 응답 없음");
		}
		if (paymentKey.startsWith("slow_")) {
			sleep();
		}
		return new GatewayPayment(paymentKey, "카드", LocalDateTime.now());
	}

	/** 테스트에서 승인 요청이 몇 번, 어떤 주문으로 들어왔는지 확인하는 데 쓴다. */
	public List<String> confirmedOrderIds() {
		return List.copyOf(confirmedOrderIds);
	}

	public void reset() {
		confirmedOrderIds.clear();
	}

	private static void sleep() {
		try {
			Thread.sleep(SLOW_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
