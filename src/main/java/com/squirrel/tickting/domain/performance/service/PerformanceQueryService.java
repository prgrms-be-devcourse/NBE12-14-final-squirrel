package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.repository.PerformanceQueryRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceSummaryRow;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.PerformanceSortType;
import com.squirrel.tickting.domain.performance.type.SaleStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매자 화면의 공연 조회. 판매자 쪽 PerformanceService 와 나눈 것은 담당자가 달라 같은 파일을 동시에 고치지 않게 하려는 것이다.
 * 공연 도메인의 값만 돌려주고, 공연장 이름·지역은 파사드가 붙인다.
 */
@Service
@RequiredArgsConstructor
public class PerformanceQueryService {

	private final PerformanceQueryRepository performanceQueryRepository;

	/**
	 * 공개된 공연 목록. 장르를 주지 않으면(null) 전체 장르다.
	 * 현재 시각은 여기서 한 번만 구해서 쿼리(남은 좌석, 임박순)와 판매 상태 계산에 같이 쓴다.
	 * pageable 은 페이지 번호와 크기만 쓰고, 정렬은 sortType 으로만 정한다.
	 */
	@Transactional(readOnly = true)
	public Page<PerformanceSummary> getPublishedList(Genre genre, PerformanceSortType sortType, Pageable pageable) {
		LocalDateTime now = LocalDateTime.now();
		Collection<Genre> genres = genre == null ? EnumSet.allOf(Genre.class) : EnumSet.of(genre);
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortType.getSort());

		return performanceQueryRepository.findPublished(genres, now, sorted)
			.map(row -> new PerformanceSummary(row, saleStatus(row, now)));
	}

	private SaleStatus saleStatus(PerformanceSummaryRow row, LocalDateTime now) {
		return SaleStatus.of(row.saleOpenAt(), row.saleCloseAt(), row.lastStartAt(), row.hasAvailableSeat(), now);
	}
}
