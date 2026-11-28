package com.squirrel.tickting.domain.admin.facade;

import com.squirrel.tickting.domain.admin.dto.AdminCommonGuideResponse;
import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.domain.audit.type.ActorType;
import com.squirrel.tickting.domain.performance.infra.entity.CommonGuide;
import com.squirrel.tickting.domain.performance.service.CommonGuideService;
import com.squirrel.tickting.domain.performance.type.GuideType;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공통 안내 수정. 공통 안내(공연 도메인)와 감사 로그(audit) 두 도메인을 엮어서, 수정과 로그 기록을 한 트랜잭션으로 묶는다.
 * 로그 없이 수정만 남거나 수정이 취소됐는데 로그만 남는 일이 없게 한다.
 */
@Component
@RequiredArgsConstructor
public class AdminCommonGuideFacade {

	private static final String TARGET_TYPE = "COMMON_GUIDE";

	private final CommonGuideService commonGuideService;
	private final AuditLogService auditLogService;
	private final TransactionTemplate transactionTemplate;

	/**
	 * 바뀌기 전 내용을 잠그고 읽은 뒤 바꾼다. 두 관리자가 동시에 고치면 한 명씩 차례로 바뀌고 나중에 저장한 쪽이 남는다.
	 * 로그에는 전후 내용을 통째로 남긴다. 처음 만든 경우 바뀌기 전 값은 비운다.
	 */
	public AdminCommonGuideResponse.Guide update(Long adminId, GuideType type, String content) {
		CommonGuide saved = transactionTemplate.execute(status -> {
			String before = commonGuideService.getContentForUpdate(type);
			CommonGuide guide = commonGuideService.save(type, content, adminId);
			auditLogService.saveHistory(AuditLogCommand.builder()
				.actorType(ActorType.ADMIN)
				.actorId(adminId)
				.targetType(TARGET_TYPE)
				.targetId(guide.getId())
				.action("UPDATE")
				.before(before == null ? null : Map.of("type", type, "content", before))
				.after(Map.of("type", type, "content", guide.getContent()))
				.build());
			return guide;
		});
		return AdminCommonGuideResponse.Guide.from(saved);
	}
}
