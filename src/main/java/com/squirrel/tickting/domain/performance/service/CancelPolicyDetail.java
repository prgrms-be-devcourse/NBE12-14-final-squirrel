package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import java.util.List;

/**
 * 취소 규정 상세 조회 결과. 규정 엔티티에는 구간 목록이 없어서 서비스 트랜잭션 안에서 구간을 함께 읽어 담는다.
 * 규정 생성·수정(SLR-21·22)도 같은 모양으로 응답하므로 이 결과를 다시 쓴다.
 */
public record CancelPolicyDetail(
	CancelPolicy policy,
	List<CancelPolicyRule> rules,
	boolean editable
) {
}
