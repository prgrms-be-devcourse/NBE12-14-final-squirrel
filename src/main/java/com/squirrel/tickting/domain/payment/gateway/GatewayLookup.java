package com.squirrel.tickting.domain.payment.gateway;

/**
 * 결제 대행사에 주문 ID 로 조회한 결과. 보정 작업이 필요한 것만 세 갈래로 나눈다.
 *
 * @param outcome 결제됐는지, 아닌지, 아직 모르는지
 * @param payment 결제된 경우의 결제 키·수단·승인 시각. 그 밖에는 null
 * @param amount  결제된 경우 대행사가 받은 금액. 우리가 계산한 금액과 같은지 확인하는 데 쓴다. 그 밖에는 null
 */
public record GatewayLookup(Outcome outcome, GatewayPayment payment, Integer amount) {

	public enum Outcome {
		/** 돈이 나갔다. (대행사 상태가 승인 완료) */
		PAID,
		/** 결제되지 않았고 앞으로도 되지 않는다. (조회되는 결제가 없거나 실패·만료·취소로 끝난 상태) */
		NOT_PAID,
		/** 아직 끝나지 않았거나 해석할 수 없는 상태. 다음 주기에 다시 조회한다. */
		UNDECIDED
	}

	public static GatewayLookup paid(GatewayPayment payment, int amount) {
		return new GatewayLookup(Outcome.PAID, payment, amount);
	}

	public static GatewayLookup notPaid() {
		return new GatewayLookup(Outcome.NOT_PAID, null, null);
	}

	public static GatewayLookup undecided() {
		return new GatewayLookup(Outcome.UNDECIDED, null, null);
	}
}
