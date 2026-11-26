package com.squirrel.tickting.domain.booking.service;

/**
 * 예매 조회에서 "누구의 예매까지 볼 수 있는가". 컨트롤러가 토큰(로그인 정보)으로만 만들고, 요청 값으로는 만들지 않는다.
 * 요청으로 사업체 번호 등을 받으면 값을 바꿔서 남의 예매를 볼 수 있기 때문이다.
 * sealed 라서 서비스의 switch 가 모든 경우를 다뤄야 컴파일된다. 범위가 늘어도 거르는 조건을 빠뜨릴 수 없다.
 */
public sealed interface BookingScope {

	/** 예매자: 내가 한 예매만 */
	record OfUser(Long userId) implements BookingScope {
	}

	/** 판매자: 내 사업체 공연의 예매만. 대표·담당자가 같은 범위를 본다 */
	record OfBusiness(Long businessId) implements BookingScope {
	}

	/** 관리자: 전체 */
	record All() implements BookingScope {
	}
}
