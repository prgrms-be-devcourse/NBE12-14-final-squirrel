package com.squirrel.tickting.domain.booking.type;

/**
 * 예매 상태. 환불의 성공·실패는 refund 가 가지므로 여기에 섞지 않는다.
 * PENDING → PAID → PARTIAL_CANCELED → CANCELED, 결제하지 않고 7분이 지나면 PENDING → EXPIRED.
 */
public enum BookingStatus {

	/** 좌석을 잡고 결제를 기다리는 중 */
	PENDING,
	/** 결제 완료 */
	PAID,
	/** 일부 좌석만 취소됨 */
	PARTIAL_CANCELED,
	/** 전체 취소됨 */
	CANCELED,
	/** 결제 제한 시간(7분)이 지나 무효 */
	EXPIRED
}
