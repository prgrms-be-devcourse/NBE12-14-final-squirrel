package com.squirrel.tickting.domain.performance.service;

import java.util.List;

/**
 * 취소 규정 생성·수정(SLR-21·22)에 넣는 값. 서비스가 요청 DTO(CancelPolicyRequest)를 모르게 하려고 따로 둔다.
 * 사업체 번호는 요청이 아니라 토큰 값이라 서비스 메서드의 인자로 따로 받는다.
 */
public record CancelPolicySaveCommand(
	String name,
	List<Rule> rules
) {

	public CancelPolicySaveCommand {
		rules = List.copyOf(rules);
	}

	/** 구간 하나. 공연 daysFrom 일 전 이상 ~ daysTo 일 전 이하(양끝 포함), daysTo 가 null 이면 제한 없음 */
	public record Rule(int daysFrom, Integer daysTo, int feeRate, boolean cancelable) {
	}
}
