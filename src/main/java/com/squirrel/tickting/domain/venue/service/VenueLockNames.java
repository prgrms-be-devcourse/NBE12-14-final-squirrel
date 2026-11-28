package com.squirrel.tickting.domain.venue.service;

/**
 * 공연장 단위 분산락 이름. 공연장 상태를 바꾸는 흐름(중지·재개, ADM-11·12)과 새 판매를 여는 흐름(공연 등록·공연장 변경·공개·회차 추가)이
 * 같은 이름을 잡아야 "사용 중인지 확인한 직후 중지"가 끼어들지 못한다. 예매·대기열·결제는 잡지 않는다.
 * 문자열을 곳곳에 쓰면 한 곳만 오타가 나도 락이 서로 듣지 않아 한 곳에 모은다.
 */
public final class VenueLockNames {

	private VenueLockNames() {
	}

	public static String venue(Long venueId) {
		return "venue:" + venueId;
	}
}
