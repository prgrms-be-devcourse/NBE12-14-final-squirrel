package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.performance.dto.UserPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.service.UserPerformanceService;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.UserPerformanceSortType;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.service.SellerBusinessService;
import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.infra.entity.VenueSeat;
import com.squirrel.tickting.domain.venue.service.VenueService;
import com.squirrel.tickting.domain.venue.service.VenueZoneInfo;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * 예매자의 공연 조회. 공연(performance)은 공연장·사업체를 번호로만 알고 있어서,
 * 서비스끼리 부르지 않고 이 파사드가 공연 서비스와 공연장·판매자 사업체 서비스를 엮어 응답으로 만든다. (판매자 쪽 SellerPerformanceFacade 와 같은 방식)
 * 읽기만 해서 트랜잭션은 각 서비스의 읽기 전용 트랜잭션으로 충분하다.
 */
@Component
@RequiredArgsConstructor
public class UserPerformanceFacade {

	private final UserPerformanceService userPerformanceService;
	private final VenueService venueService;
	private final SellerBusinessService sellerBusinessService;

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
			page.getContent().stream().map(summary -> summary.performance().getVenueId()).distinct().toList());

		return page.map(summary -> UserPerformanceResponse.Summary.from(summary, venues.get(summary.performance().getVenueId())));
	}

	/**
	 * 공연 상세. 공연 도메인의 값은 공연 서비스가 한 번에 읽고, 공연장과 판매자 사업체는 각 서비스에서 받아 붙인다.
	 * 둘 다 논리 FK 라 행이 없을 수 있어서, 없으면 그 부분만 비우고(null) 상세는 그대로 보여 준다.
	 */
	public UserPerformanceResponse.Detail getDetail(Long performanceId) {
		UserPerformanceService.Detail detail = userPerformanceService.getPublishedDetail(performanceId);
		Performance performance = detail.performance();

		Venue venue = venueService.getVenues(List.of(performance.getVenueId())).get(performance.getVenueId());
		SellerBusiness seller = sellerBusinessService.findBusiness(performance.getBusinessId()).orElse(null);

		return UserPerformanceResponse.Detail.from(detail, venue, seller);
	}

	/**
	 * 회차의 구역 요약. 등급과 잔여석은 공연 서비스가, 구역 이름·순서와 구역별 좌석 수·도면 키는 공연장 서비스가 준다.
	 * 구역 정보는 판매자 등급·구역 조회(SLR-08)와 같은 getZoneInfos 를 쓴다.
	 * 공연장은 논리 FK 라 행이 없을 수 있어서, 없으면 도면 키만 비운다(null). (구역도 없으니 구역 목록은 빈다)
	 */
	public UserPerformanceResponse.ZoneSummary getZoneSummary(Long scheduleId) {
		UserPerformanceService.ZoneSummary summary = userPerformanceService.getZoneSummary(scheduleId);
		Long venueId = summary.schedule().getPerformance().getVenueId();

		Venue venue = venueService.getVenues(List.of(venueId)).get(venueId);
		Map<Long, VenueZoneInfo> zones = venueService.getZoneInfos(venueId);

		return UserPerformanceResponse.ZoneSummary.from(summary, venue, zones.values());
	}

	/**
	 * 회차의 한 구역 좌석. 상태는 공연 서비스(판매 좌석)가, 열·번호·표시 순서와 구역 이름은 공연장 서비스가 준다.
	 * 구역이 그 회차의 공연장 것이 아니면(없는 구역 포함) 404 다. 다른 공연장의 구역 번호로 좌석이 섞여 보이지 않게 한다.
	 */
	public UserPerformanceResponse.ZoneSeats getZoneSeats(Long scheduleId, Long zoneId) {
		UserPerformanceService.ZoneSeats zoneSeats = userPerformanceService.getZoneSeats(scheduleId, zoneId);
		Long venueId = zoneSeats.schedule().getPerformance().getVenueId();

		VenueZoneInfo zone = venueService.getZoneInfos(venueId).get(zoneId);
		if (zone == null) {
			throw new BusinessException(PerformanceExceptionCode.USER_ZONE_NOT_FOUND);
		}
		List<VenueSeat> venueSeats = venueService.getZoneSeats(zoneId);

		return UserPerformanceResponse.ZoneSeats.from(zone, venueSeats, zoneSeats.seats());
	}
}
