package com.squirrel.tickting.domain.performance.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;

/**
 * 예매자 공연 목록의 정렬 기준. 프론트는 이 이름(sort=LATEST)만 보내고, 실제 정렬 조건은 여기서만 정한다.
 * 그래서 DB 컬럼 이름이 밖에 드러나지 않고, 기준을 추가할 때 이 파일에 한 줄만 더하면 된다.
 *
 * 값이 같은 공연끼리 순서가 매번 바뀌면 페이지를 넘길 때 같은 공연이 두 번 나오거나 빠진다.
 * 그래서 모든 기준의 마지막에 공연 번호를 붙여 순서를 하나로 고정한다.
 *
 * 정렬은 공연(Performance)의 필드로만 한다. 목록 쿼리가 공연 테이블만 읽고 회차는 서비스가 따로 읽기 때문이다.
 * 회차 일시로 정렬하는 기준(공연 임박순 등)을 넣으려면 목록 쿼리에서 회차를 묶어 계산하도록 바꿔야 한다.
 */
@Getter
@RequiredArgsConstructor
public enum UserPerformanceSortType {

	/** 최신 등록순 */
	LATEST(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

	private final Sort sort;
}
