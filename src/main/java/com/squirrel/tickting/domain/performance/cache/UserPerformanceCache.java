package com.squirrel.tickting.domain.performance.cache;

import com.squirrel.tickting.domain.performance.dto.UserPerformanceResponse;
import com.squirrel.tickting.domain.performance.service.UserPerformanceService;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.UserPerformanceSortType;
import com.squirrel.tickting.global.cache.ReadCache;
import com.squirrel.tickting.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/**
 * 예매자 공연 조회 중 몇 초 낡아도 되는 것(목록·상세·구역 요약)을 Redis 에 잠깐 저장해 DB 를 덜 읽게 한다.
 * 컨트롤러가 서비스 앞에서 이 클래스를 거친다. 서비스의 @Transactional 안에서 캐시를 확인하면 적중해도 DB 연결을 빌리게 되어서,
 * 트랜잭션 바깥에 둔다. (서비스가 서비스를 부르는 구조가 아니라 컨트롤러 앞의 읽기 캐시다)
 *
 * 캐시하지 않는 것:
 *   - 구역 좌석 상세: 좌석 상태(점유·판매)가 곧바로 바뀌고, 그 화면에서 고른 좌석이 점유 요청으로 이어진다. 항상 DB 에서 읽는다.
 *   - 검색어가 있는 목록: 사용자가 마음대로 정하는 값이라 키가 끝없이 늘 수 있고, 같은 검색이 반복될 가능성도 낮다.
 *   - 없는 공연·회차의 404: 예외는 저장하지 않는다.
 *
 * 판매자가 공연을 고치거나 판매 상태가 바뀌어도 지우지 않고 TTL 로만 반영한다. 최대 TTL 만큼 이전 값이 보이지만,
 * 예매 가능 여부는 점유·결제 단계에서 DB 로 다시 확인하므로 낡은 화면이 잘못된 예매로 이어지지 않는다.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(PerformanceCacheProperties.class)
public class UserPerformanceCache {

	/** 지역은 사용자가 마음대로 정하는 값이다. 실제 지역 이름보다 훨씬 긴 값은 캐시 키로 쓰지 않는다. */
	private static final int MAX_CACHED_REGION_LENGTH = 20;
	private static final String DETAIL = "performance-detail";

	private final ReadCache readCache;
	private final PerformanceCacheProperties properties;
	private final UserPerformanceService userPerformanceService;

	/** 장르·지역·정렬·페이지가 같으면 같은 응답이다. 검색어가 있으면 캐시하지 않는다. */
	public PageResponse<UserPerformanceResponse.Summary> getPublishedList(Genre genre, String region, String keyword,
		UserPerformanceSortType sortType, Pageable pageable) {
		String trimmedRegion = region == null ? "" : region.trim();
		boolean hasKeyword = keyword != null && !keyword.isBlank();
		if (hasKeyword || trimmedRegion.length() > MAX_CACHED_REGION_LENGTH) {
			return loadList(genre, region, keyword, sortType, pageable);
		}

		String key = (genre == null ? "ALL" : genre.name()) + ":" + trimmedRegion + ":" + sortType.name()
			+ ":" + pageable.getPageNumber() + ":" + pageable.getPageSize();
		return readCache.getOrLoad("performance-list", key, properties.effective(properties.listTtl()),
			new TypeReference<PageResponse<UserPerformanceResponse.Summary>>() {
			}, () -> loadList(genre, region, keyword, sortType, pageable));
	}

	public UserPerformanceResponse.Detail getPublishedDetail(Long performanceId) {
		return readCache.getOrLoad(DETAIL, String.valueOf(performanceId),
			properties.effective(properties.detailTtl()), new TypeReference<UserPerformanceResponse.Detail>() {
			}, () -> userPerformanceService.getPublishedDetail(performanceId));
	}

	/** 판매 중지·재개와 연기·확정은 상세에 바로 보여야 해서 저장분을 지운다. 중지·연기되면 화면이 예매자를 상세로 보내기 때문이다. */
	public void evictDetail(Long performanceId) {
		readCache.evict(DETAIL, String.valueOf(performanceId));
	}

	public UserPerformanceResponse.ZoneSummary getZoneSummary(Long scheduleId) {
		return readCache.getOrLoad("schedule-zones", String.valueOf(scheduleId),
			properties.effective(properties.zoneSummaryTtl()), new TypeReference<UserPerformanceResponse.ZoneSummary>() {
			}, () -> userPerformanceService.getZoneSummary(scheduleId));
	}

	private PageResponse<UserPerformanceResponse.Summary> loadList(Genre genre, String region, String keyword,
		UserPerformanceSortType sortType, Pageable pageable) {
		return PageResponse.from(userPerformanceService.getPublishedList(genre, region, keyword, sortType, pageable));
	}
}
