package com.squirrel.tickting.domain.booking.type;

/** 예매자가 확정한 티켓 수령 방법. 공연의 허용 범위(BOTH 포함)와 달리 둘 중 하나다. */
public enum ReceiveMethod {

	/** 현장 수령 */
	ONSITE,
	/** 배송 */
	DELIVERY
}
