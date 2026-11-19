package com.squirrel.tickting.domain.performance.service;

/**
 * 판매자 공연 기능의 분산락 이름. 등급 수정(SLR-10)·구역 설정(SLR-11)·공개(SLR-04)·취소 규정 수정(SLR-22)이
 * 같은 이름을 잡아야 서로 막는다. 판매 좌석 생성(C님)도 공연 락 이름을 이걸로 맞춘다.
 * 문자열을 곳곳에 쓰면 한 곳만 오타가 나도 락이 서로 듣지 않아 조용히 틀리므로 한 곳에 모은다.
 */
public final class PerformanceLockNames {

	private PerformanceLockNames() {
	}

	/** 공연 단위 락. 구역·등급(가격)이 좌석 생성 때 복사되는 것과 공개 조건 확인이 겹치지 않게 한다. */
	public static String performance(Long performanceId) {
		return "performance:" + performanceId;
	}

	/** 취소 규정 단위 락. 공개한 공연이 쓰는 규정이 바뀌지 않게, 공개와 규정 수정이 같은 이름을 잡는다. */
	public static String cancelPolicy(Long cancelPolicyId) {
		return "cancel-policy:" + cancelPolicyId;
	}
}
