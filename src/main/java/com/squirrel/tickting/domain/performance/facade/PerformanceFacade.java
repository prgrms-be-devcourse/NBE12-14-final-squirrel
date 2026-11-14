package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.performance.dto.PerformanceResponse;
import com.squirrel.tickting.domain.performance.service.PerformanceQueryService;
import com.squirrel.tickting.domain.performance.service.PerformanceSummary;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.PerformanceSortType;
import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.service.VenueService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * 예매자의 공연 조회. 공연(performance)은 공연장을 번호로만 알고 있어서,
 * 서비스끼리 부르지 않고 이 파사드가 공연 서비스와 공연장 서비스를 엮어 응답으로 만든다. (판매자 쪽 SellerPerformanceFacade 와 같은 방식)
 * 읽기만 해서 트랜잭션은 각 서비스의 읽기 전용 트랜잭션으로 충분하다.
 */
@Component
@RequiredArgsConstructor
public class PerformanceFacade {

	private final PerformanceQueryService performanceQueryService;
	private final VenueService venueService;

	/** 공연장은 공연마다 조회하지 않고, 한 페이지에 나온 공연장 번호 목록으로 한 번에 읽는다. */
	public Page<PerformanceResponse.Summary> getList(Genre genre, PerformanceSortType sortType, Pageable pageable) {
		Page<PerformanceSummary> page = performanceQueryService.getPublishedList(genre, sortType, pageable);

		Map<Long, Venue> venues = venueService.getVenues(
			page.getContent().stream().map(summary -> summary.row().venueId()).distinct().toList());

		return page.map(summary -> PerformanceResponse.Summary.from(summary, venues.get(summary.row().venueId())));
	}
}
