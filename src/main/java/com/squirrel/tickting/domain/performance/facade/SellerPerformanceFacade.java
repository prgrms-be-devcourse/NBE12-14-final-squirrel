package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.performance.dto.SellerPerformanceRequest;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.service.CancelPolicyService;
import com.squirrel.tickting.domain.performance.service.GradeService;
import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import com.squirrel.tickting.domain.venue.service.VenueService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자의 공연 등록. 공연장(venue)·취소 규정(cancel policy)·공연(performance)·구역 설정(grade) 서비스를 엮고,
 * 쓰기는 TransactionTemplate 으로 한 트랜잭션에 묶는다. (조회는 공연의 읽기 전용 연관으로 서비스가 직접 응답을 만든다)
 */
@Component
@RequiredArgsConstructor
public class SellerPerformanceFacade {

	private final SellerPerformanceService sellerPerformanceService;
	private final VenueService venueService;
	private final CancelPolicyService cancelPolicyService;
	private final GradeService gradeService;
	private final TransactionTemplate transactionTemplate;

	/**
	 * 공연을 작성 중(DRAFT)으로 등록하고 공연장의 모든 구역을 UNSET 으로 만든다.
	 * 확인(공연장, 취소 규정)은 읽기만 해서 트랜잭션 밖에서 먼저 하고, 쓰기만 한 트랜잭션으로 묶는다.
	 * 구역 설정 없이 공연만 남으면 공개 검사가 빈 구역을 "UNSET 없음"으로 잘못 읽으므로 둘은 함께 성공하거나 함께 취소된다.
	 * 잠금은 쓰지 않는다. 새 공연은 DRAFT 라 취소 규정 수정과 부딪히지 않고, 공연장 구역은 등록 중에 바뀌지 않는다.
	 */
	public SellerPerformanceResponse.Created register(Long businessId, Long createdBy,
		SellerPerformanceRequest.Save request) {
		List<Long> zoneIds = venueService.getActiveZoneIds(request.venueId());
		CancelPolicy cancelPolicy = cancelPolicyService.getVisible(request.cancelPolicyId(), businessId);

		Performance performance = transactionTemplate.execute(status -> {
			Performance created = sellerPerformanceService.create(businessId, createdBy, request.venueId(), cancelPolicy,
				request.toCommand());
			gradeService.initializeZones(created, zoneIds);
			return created;
		});
		return SellerPerformanceResponse.Created.from(performance);
	}
}
