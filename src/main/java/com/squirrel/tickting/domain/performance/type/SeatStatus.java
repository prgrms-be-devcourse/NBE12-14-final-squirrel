package com.squirrel.tickting.domain.performance.type;

/**
 * 회차별 판매 좌석의 상태.
 * HELD(임시 점유)와 RESERVED(판매자 보류)는 이름이 비슷해서 헷갈리니 팀에서는 "임시 점유", "판매자 보류석"으로 부른다.
 */
public enum SeatStatus {

	/** 판매 가능 */
	AVAILABLE,
	/** 결제 중 임시 점유. held_by, held_until 이 채워진다. */
	HELD,
	/** 판매 완료 */
	SOLD,
	/** 판매 제외 (무대 설치, 시야 방해, 등급 미지정 구역) */
	BLOCKED,
	/** 판매자 보류 (초대권, 협찬용). 나중에 풀어서 판매할 수 있다. */
	RESERVED
}
