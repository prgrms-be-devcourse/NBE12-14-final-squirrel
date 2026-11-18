package com.squirrel.tickting.domain.performance.type;

/**
 * 좌석 등급 코드. 판매자가 이름을 자유롭게 지으면 눈으로 같은 등급(R석·R 석·r석)이 여럿 생기고 공연마다 표기가 달라져서
 * 실제 예매처에서 쓰는 등급명으로 목록을 정했다. 화면에 보이는 이름("VIP석")과 색은 프론트가 코드마다 정한다.
 * 코드는 추가만 하고 이름을 바꾸거나 지우지 않는다. 예매 등에 이미 저장된 코드를 프론트가 계속 보여 줘야 하기 때문이다.
 */
public enum SeatGradeName {
	VIP,
	R,
	S,
	A,
	B,
	C,
	/** OP석(오케스트라 피트 위 좌석) */
	OP,
	/** 시야제한석 */
	VIEW_LIMITED,
	/** 전석. 다른 등급과 함께 구역에 지정할 수 없다. (함께 보이면 "전석"이 틀린 말이 된다) */
	ALL,
	/** 스탠딩석 */
	STANDING,
	/** VIP 스탠딩석 */
	VIP_STANDING,
	/** 지정석 */
	RESERVED
}
