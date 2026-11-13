package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.service.PerformanceService;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.venue.service.VenueService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * 판매자의 내 공연 조회. 공연(performance)은 공연장 이름을 번호로만 알고 있어서,
 * 서비스끼리 부르지 않고 이 파사드가 공연 서비스와 공연장 서비스를 엮어 응답으로 만든다.
 * 읽기만 해서 트랜잭션은 각 서비스의 읽기 전용 트랜잭션으로 충분하다.
 */
@Component
@RequiredArgsConstructor
public class SellerPerformanceFacade {

	private final PerformanceService performanceService;
	private final VenueService venueService;

	/** 목록의 회차 수·공연장 이름은 공연마다 조회하지 않고 번호 목록으로 한 번에 읽는다. */
	public Page<SellerPerformanceResponse.Summary> getList(Long businessId, PerformanceStatus status,
		Pageable pageable) {
		Page<Performance> page = performanceService.searchMine(businessId, status, pageable);
		List<Performance> performances = page.getContent();

		Map<Long, Long> scheduleCounts = performanceService.countSchedules(
			performances.stream().map(Performance::getId).toList());
		Map<Long, String> venueNames = venueService.getNames(
			performances.stream().map(Performance::getVenueId).distinct().toList());

		return page.map(performance -> SellerPerformanceResponse.Summary.from(
			performance,
			venueNames.get(performance.getVenueId()),
			scheduleCounts.getOrDefault(performance.getId(), 0L)));
	}
}
