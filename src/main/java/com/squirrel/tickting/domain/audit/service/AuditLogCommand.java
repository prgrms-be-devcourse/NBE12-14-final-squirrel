package com.squirrel.tickting.domain.audit.service;

import com.squirrel.tickting.domain.audit.type.ActorType;
import lombok.Builder;

/**
 * 감사 로그 한 줄을 남기는 데 필요한 값. 인자가 많아서 순서를 헷갈리지 않도록 빌더로 만든다.
 *
 * @param businessId 판매자 이력 조회용. 관리자·시스템 행동은 비운다. (판매자의 이력 화면에 관리자 처리 로그가 섞이지 않게)
 * @param before     바뀌기 전 값. 대상마다 모양이 달라서 JSON 으로 저장하므로 Map 이나 record 를 넘긴다. 없으면 null
 * @param after      바뀐 뒤 값. before 와 같은 모양으로 넘긴다. 없으면 null
 */
@Builder
public record AuditLogCommand(
	ActorType actorType,
	Long actorId,
	Long businessId,
	String targetType,
	Long targetId,
	String action,
	Object before,
	Object after
) {
}
