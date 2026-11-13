package com.squirrel.tickting.domain.audit.service;

import com.squirrel.tickting.domain.audit.infra.entity.AuditLog;
import com.squirrel.tickting.domain.audit.infra.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class AuditLogService {

	private final AuditLogRepository auditLogRepository;
	private final ObjectMapper objectMapper;

	/**
	 * 변경 행동 한 줄을 이력에 추가한다. 호출하는 쪽의 트랜잭션에 참여해서, 변경이 취소되면 이력도 같이 취소된다.
	 * 변경 전후 값은 대상마다 모양이 달라서 객체를 JSON 으로 바꿔 저장한다.
	 */
	@Transactional
	public void saveHistory(AuditLogCommand command) {
		auditLogRepository.save(AuditLog.builder()
			.actorType(command.actorType())
			.actorId(command.actorId())
			.businessId(command.businessId())
			.targetType(command.targetType())
			.targetId(command.targetId())
			.action(command.action())
			.beforeValue(toJson(command.before()))
			.afterValue(toJson(command.after()))
			.build());
	}

	private String toJson(Object value) {
		return value == null ? null : objectMapper.writeValueAsString(value);
	}
}
