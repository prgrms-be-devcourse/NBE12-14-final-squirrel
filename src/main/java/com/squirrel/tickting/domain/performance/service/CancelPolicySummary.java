package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;

/** 취소 규정 목록의 한 줄. 지금 고칠 수 있는지(editable)를 함께 담는다. */
public record CancelPolicySummary(
	CancelPolicy policy,
	boolean editable
) {
}
