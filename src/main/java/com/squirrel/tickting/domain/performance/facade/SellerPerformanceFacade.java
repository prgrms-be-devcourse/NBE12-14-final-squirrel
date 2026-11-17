package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.performance.dto.SellerPerformanceRequest;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.service.CancelPolicyService;
import com.squirrel.tickting.domain.performance.service.GradeService;
import com.squirrel.tickting.domain.performance.service.PerformanceDetail;
import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.venue.service.VenueService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자의 내 공연 조회·등록. 공연(performance)은 공연장을 번호로만 알고 있어서,
 * 서비스끼리 부르지 않고 이 파사드가 공연 서비스와 공연장 서비스를 엮는다.
 * 조회는 각 서비스의 읽기 전용 트랜잭션으로 충분하고, 등록의 쓰기는 TransactionTemplate 으로 묶는다.
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

	/** 목록의 회차 수·공연장 이름은 공연마다 조회하지 않고 번호 목록으로 한 번에 읽는다. */
	public Page<SellerPerformanceResponse.Summary> getList(Long businessId, PerformanceStatus status,
		Pageable pageable) {
		Page<Performance> page = sellerPerformanceService.searchMine(businessId, status, pageable);
		List<Performance> performances = page.getContent();

		Map<Long, Long> scheduleCounts = sellerPerformanceService.countSchedules(
			performances.stream().map(Performance::getId).toList());
		Map<Long, String> venueNames = venueService.getNames(
			performances.stream().map(Performance::getVenueId).distinct().toList());

		return page.map(performance -> SellerPerformanceResponse.Summary.from(
			performance,
			venueNames.get(performance.getVenueId()),
			scheduleCounts.getOrDefault(performance.getId(), 0L)));
	}

	public SellerPerformanceResponse.Detail getDetail(Long businessId, Long performanceId) {
		PerformanceDetail detail = sellerPerformanceService.getMine(businessId, performanceId);
		Long venueId = detail.performance().getVenueId();
		String venueName = venueService.getNames(List.of(venueId)).get(venueId);
		return SellerPerformanceResponse.Detail.from(detail, venueName);
	}
}
