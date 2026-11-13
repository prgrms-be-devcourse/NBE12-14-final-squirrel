package com.squirrel.tickting.domain.admin.facade;

import com.squirrel.tickting.domain.admin.dto.AdminSellerBusinessResponse;
import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.domain.audit.type.ActorType;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.service.SellerBusinessService;
import com.squirrel.tickting.domain.seller.type.SellerBusinessStatus;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사업체 승인·반려처럼 사업체(seller)와 감사 로그(audit) 두 도메인을 엮는 관리자 흐름.
 * 서비스끼리 부르지 않으므로 "상태 변경 + 로그 기록"을 한 트랜잭션으로 묶는 일은 여기서 한다.
 */
@Component
@RequiredArgsConstructor
public class AdminSellerBusinessFacade {

	private static final String TARGET_TYPE = "SELLER_BUSINESS";

	private final SellerBusinessService sellerBusinessService;
	private final AuditLogService auditLogService;
	private final TransactionTemplate transactionTemplate;

	/** 승인과 감사 로그는 함께 성공하거나 함께 취소된다. 로그 없이 승인만 남는 일이 없도록 한 트랜잭션이다. */
	public AdminSellerBusinessResponse.Approval approve(Long adminId, Long businessId) {
		SellerBusiness business = transactionTemplate.execute(status -> {
			SellerBusiness approved = sellerBusinessService.approve(adminId, businessId);
			auditLogService.saveHistory(AuditLogCommand.builder()
				.actorType(ActorType.ADMIN)
				.actorId(adminId)
				.targetType(TARGET_TYPE)
				.targetId(businessId)
				.action("APPROVE")
				.before(Map.of("status", SellerBusinessStatus.PENDING))
				.after(Map.of("status", approved.getStatus()))
				.build());
			return approved;
		});
		return AdminSellerBusinessResponse.Approval.from(business);
	}
}
