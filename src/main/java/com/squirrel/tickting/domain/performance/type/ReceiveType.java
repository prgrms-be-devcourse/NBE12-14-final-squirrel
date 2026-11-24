package com.squirrel.tickting.domain.performance.type;

/** 공연에서 허용하는 티켓 수령 방법. 예매에는 이 중 하나가 확정되어 저장된다. */
public enum ReceiveType {

	/** 현장 수령 */
	ONSITE,
	/** 배송 */
	DELIVERY,
	/** 예매자가 선택 */
	BOTH
}
