package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.performance.dto.UserPerformanceResponse;
import com.squirrel.tickting.domain.performance.service.UserPerformanceService;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.UserPerformanceSortType;
import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.service.VenueService;
import java.util.List;
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
public class UserPerformanceFacade {

	private final UserPerformanceService userPerformanceService;
	private final VenueService venueService;

	/**
	 * 지역은 공연장에 있어서, 지역을 고르면 그 지역의 공연장 번호 목록을 먼저 받아 공연 조회 조건으로 넘긴다.
	 * 지역을 고르지 않았으면(null·공백) 공연장을 따지지 않는다. (null 을 넘긴다)
	 * 공연장 이름·지역은 공연마다 조회하지 않고, 한 페이지에 나온 공연장 번호 목록으로 한 번에 읽는다.
	 */
	public Page<UserPerformanceResponse.Summary> getList(Genre genre, String region, String keyword,
		UserPerformanceSortType sortType, Pageable pageable) {
		List<Long> venueIds = region == null || region.isBlank() ? null : venueService.getIdsByRegion(region.trim());

		Page<UserPerformanceService.Summary> page =
			userPerformanceService.getPublishedList(genre, venueIds, keyword, sortType, pageable);

		Map<Long, Venue> venues = venueService.getVenues(
			page.getContent().stream().map(summary -> summary.row().venueId()).distinct().toList());

		return page.map(summary -> UserPerformanceResponse.Summary.from(summary, venues.get(summary.row().venueId())));
	}
}
