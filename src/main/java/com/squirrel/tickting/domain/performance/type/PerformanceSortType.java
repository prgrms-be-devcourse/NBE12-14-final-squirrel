package com.squirrel.tickting.domain.performance.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;

/**
 * 예매자 공연 목록의 정렬 기준. 프론트는 이 이름(sort=LATEST)만 보내고, 실제 정렬 조건은 여기서만 정한다.
 * 그래서 DB 컬럼 이름이 밖에 드러나지 않고, 기준을 추가·수정할 때 이 파일의 한 줄만 바꾸면 된다.
 *
 * 값이 같은 공연끼리 순서가 매번 바뀌면 페이지를 넘길 때 같은 공연이 두 번 나오거나 빠진다.
 * 그래서 모든 기준의 마지막에 공연 번호를 붙여 순서를 하나로 고정한다.
 *
 * 정렬 조건은 PerformanceQueryRepository.findPublished 쿼리의 별칭(p: 공연, s: 회차)을 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum PerformanceSortType {

	/**
	 * 공연 임박순. "첫 회차"가 아니라 아직 시작하지 않은 회차 중 가장 빠른 일시를 본다.
	 * 첫 회차로 정렬하면 이미 시작했거나 끝난 공연의 과거 날짜가 맨 앞에 온다.
	 * 남은 회차가 없는 공연은 값이 비어(null) 맨 뒤로 간다. (PostgreSQL 은 오름차순에서 null 이 마지막)
	 */
	IMMINENT(JpaSort.unsafe(Sort.Direction.ASC, "MIN(CASE WHEN s.startAt > :now THEN s.startAt END)")
		.and(Sort.by(Sort.Direction.ASC, "id"))),

	/** 최신 등록순 */
	LATEST(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))),

	/** 이름순 (가나다순) */
	TITLE(Sort.by(Sort.Direction.ASC, "title").and(Sort.by(Sort.Direction.ASC, "id")));

	private final Sort sort;
}
