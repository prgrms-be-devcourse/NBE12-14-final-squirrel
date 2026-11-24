package com.squirrel.tickting.domain.performance.type;

import java.time.LocalDateTime;

/**
 * 예매자에게 보여 주는 공연의 판매 상태. 저장하지 않고 조회할 때마다 계산한다. (3부: 저장하는 상태가 적을수록 값이 어긋나는 버그가 준다)
 * 공연 목록과 공연 상세가 같은 규칙을 쓰도록 계산을 여기 한 곳에 둔다.
 */
public enum SaleStatus {

	/** 판매 오픈 전 */
	UPCOMING,
	/** 판매 중 */
	ON_SALE,
	/** 남은 좌석이 없음 */
	SOLD_OUT,
	/** 판매 종료 (판매 종료 시각이 지났거나 마지막 회차가 시작됨) */
	ENDED;

	/**
	 * 판매 상태를 계산한다. 현재 시각을 밖에서 받아서, 한 요청 안에서 기준 시각이 어긋나지 않고 경계값을 테스트하기 쉽다.
	 * 종료를 가장 먼저 본다. 끝난 공연은 좌석이 남아 있어도 살 수 없기 때문이다.
	 *
	 * @param lastStartAt       취소되지 않은 회차 중 가장 늦은 공연 일시. 회차가 없으면 null 이고 살 수 있는 회차가 없으니 종료로 본다
	 * @param hasAvailableSeat  아직 시작하지 않은 회차에 판매 가능(AVAILABLE) 좌석이 하나라도 있는지
	 */
	public static SaleStatus of(LocalDateTime saleOpenAt, LocalDateTime saleCloseAt, LocalDateTime lastStartAt,
		boolean hasAvailableSeat, LocalDateTime now) {
		if (lastStartAt == null || !now.isBefore(lastStartAt) || !now.isBefore(saleCloseAt)) {
			return ENDED;
		}
		if (now.isBefore(saleOpenAt)) {
			return UPCOMING;
		}
		if (!hasAvailableSeat) {
			return SOLD_OUT;
		}
		return ON_SALE;
	}
}
