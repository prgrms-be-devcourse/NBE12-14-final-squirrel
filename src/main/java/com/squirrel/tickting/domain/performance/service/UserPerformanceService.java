package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.SaleStatus;
import com.squirrel.tickting.domain.performance.type.UserPerformanceSortType;
import com.squirrel.tickting.global.util.LikeUtil;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매자(User) 화면의 공연 조회. 공연 도메인을 판매자와 함께 쓰므로, 같은 파일을 동시에 고치지 않게 역할 이름을 붙여 나눈다.
 * 공연 도메인의 값만 돌려주고, 공연장 이름·지역은 파사드가 붙인다.
 */
@Service
@RequiredArgsConstructor
public class UserPerformanceService {

	private final UserPerformanceRepository userPerformanceRepository;

	/**
	 * 공개된 공연 목록·검색. 장르를 주지 않으면(null) 전체 장르다.
	 * venueIds 가 null 이면 공연장(지역)을 따지지 않고, 있으면 그 공연장의 공연만 찾는다. 검색어는 제목·출연진의 부분 일치다.
	 * 현재 시각은 여기서 한 번만 구해서 쿼리(남은 좌석, 임박순)와 판매 상태 계산에 같이 쓴다.
	 * pageable 은 페이지 번호와 크기만 쓰고, 정렬은 sortType 으로만 정한다.
	 */
	@Transactional(readOnly = true)
	public Page<Summary> getPublishedList(Genre genre, Collection<Long> venueIds, String keyword,
		UserPerformanceSortType sortType, Pageable pageable) {
		LocalDateTime now = LocalDateTime.now();
		Collection<Genre> genres = genre == null ? EnumSet.allOf(Genre.class) : EnumSet.of(genre);
		boolean allVenues = venueIds == null;
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortType.getSort());

		return userPerformanceRepository.findPublished(genres, allVenues, allVenues ? List.of() : venueIds,
				LikeUtil.containsPattern(keyword), now, sorted)
			.map(row -> new Summary(row, saleStatus(row, now)));
	}

	private SaleStatus saleStatus(UserPerformanceRepository.SummaryRow row, LocalDateTime now) {
		return SaleStatus.of(row.saleOpenAt(), row.saleCloseAt(), row.lastStartAt(), row.hasAvailableSeat(), now);
	}

	/** 공연 목록의 한 항목. 쿼리로 읽은 값과 그 값으로 계산한 판매 상태를 함께 담는다. */
	public record Summary(UserPerformanceRepository.SummaryRow row, SaleStatus saleStatus) {
	}
}
