package com.squirrel.tickting.domain.payment.facade;

import com.squirrel.tickting.domain.payment.gateway.GatewayRejectedException;
import com.squirrel.tickting.domain.payment.gateway.GatewayUnavailableException;
import com.squirrel.tickting.domain.payment.gateway.PaymentGateway;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이미 가져온(요청 중으로 바꾼) 환불을 토스에 다시 보낸다. 재시도 스케줄러와 관리자 재처리가 같이 쓴다.
 * 처음과 같은 멱등키·금액·사유를 보내야 토스가 같은 요청으로 알아봐서, 보내는 값을 여기 한곳에서 정한다.
 * DB 트랜잭션 밖에서 불러야 한다. (토스 응답을 기다리는 동안 DB 연결을 붙잡지 않게)
 */
@Component
@RequiredArgsConstructor
public class RefundSender {

	private final PaymentGateway paymentGateway;

	/**
	 * 거절과 결과를 모르는 경우 모두 실패 사유로 돌려준다. 결과를 모르는 경우도 같은 멱등키로 다시 보내면 이어서 처리되므로 실패로 둔다.
	 * 0원 환불(수수료 100% 규정)은 토스가 받지 않아서 보내지 않고 성공으로 본다.
	 *
	 * @param refund 결제를 함께 읽은 환불 (결제 키가 필요하다)
	 * @return 실패 사유. 성공이면 null
	 */
	public String send(Refund refund) {
		if (refund.getAmount() == 0) {
			return null;
		}
		try {
			paymentGateway.cancel(refund.getPayment().getPaymentKey(), refund.getAmount(),
				refund.getReasonType().getGatewayReason(), refund.getIdempotencyKey());
			return null;
		} catch (GatewayRejectedException e) {
			return e.getCode() + ": " + e.getMessage();
		} catch (GatewayUnavailableException e) {
			return "환불 결과를 알 수 없음: " + e.getMessage();
		}
	}
}
