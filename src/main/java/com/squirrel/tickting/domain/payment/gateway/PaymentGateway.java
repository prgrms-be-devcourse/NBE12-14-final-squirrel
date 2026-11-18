package com.squirrel.tickting.domain.payment.gateway;

/**
 * 결제 대행사(토스) 연동 경계. 외부 서비스라서 인터페이스로 두고, 설정(app.payment.gateway)으로 구현을 고른다.
 * 호출은 DB 트랜잭션 밖에서 해야 한다. 응답이 느리면 그동안 DB 연결을 붙잡게 된다.
 */
public interface PaymentGateway {

	/**
	 * 결제를 승인한다. 결제창에서 인증이 끝난 결제의 돈을 실제로 빼는 단계다.
	 * 같은 주문 ID 로 다시 불러도 결과가 같아야 한다. (토스는 멱등키로 보장한다)
	 *
	 * @throws GatewayRejectedException    승인을 거절했다. 카드사 거절, 한도 초과, 시간 초과된 결제 세션 등 다시 해도 소용없는 경우
	 * @throws GatewayUnavailableException 승인됐는지 알 수 없다. 통신 오류, 시간 초과, 서버 오류, 이미 처리된 결제 등
	 */
	GatewayPayment confirm(String paymentKey, String orderId, int amount);
}
