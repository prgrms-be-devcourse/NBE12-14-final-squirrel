package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.dto.UserPerformanceResponse;
import com.squirrel.tickting.domain.performance.facade.UserPerformanceFacade;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.UserPerformanceSortType;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 예매자 화면의 공연 조회. 로그인 없이 볼 수 있다. (SecurityConfig 의 PUBLIC_READ_PATHS) */
@Tag(name = "Performance", description = "예매자 공연 조회 API")
@RestController
@RequestMapping("/api/v1/performances")
@RequiredArgsConstructor
public class UserPerformanceController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	/** 공연장·판매자 사업체를 각 서비스에서 받아 붙여야 해서 파사드를 부른다. */
	private final UserPerformanceFacade userPerformanceFacade;

	@Operation(summary = "공연 목록·검색",
		description = "공개된 공연을 보여 준다. 장르를 주지 않으면 전체, 주면 그 장르만 나온다. "
			+ "지역(region)은 공연장 지역과 정확히 같은 것만, 검색어(keyword)는 제목·출연진에 들어 있는 것을 찾는다. "
			+ "정렬은 IMMINENT(공연 임박순, 기본) · LATEST(최신 등록순) · TITLE(이름순)이고, 한 번에 최대 20개까지 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<UserPerformanceResponse.Summary>>> list(
		@Parameter(description = "장르. 비우면 전체")
		@RequestParam(required = false) Genre genre,
		@Parameter(description = "공연장 지역 (정확히 일치). 비우면 전체", example = "서울")
		@RequestParam(required = false) String region,
		@Parameter(description = "검색어. 제목·출연진 부분 일치, 대소문자 무시. 비우면 전체")
		@RequestParam(required = false) String keyword,
		@Parameter(description = "정렬. 기본 IMMINENT")
		@RequestParam(defaultValue = "IMMINENT") UserPerformanceSortType sort,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 정렬은 sort 값으로만 정한다. Pageable 이 같은 이름의 파라미터를 정렬 컬럼으로 읽어도 쓰지 않고 번호와 크기만 넘긴다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		Page<UserPerformanceResponse.Summary> page = userPerformanceFacade.getList(genre, region, keyword, sort, pageRequest);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

	@Operation(summary = "공연 상세",
		description = "공개된 공연 하나의 상세를 준다. 작성 중·취소된 공연은 404 다. "
			+ "취소된 회차는 빠지고, 등급은 구역에 지정된 것만 비싼 순으로 나온다.")
	@GetMapping("/{performanceId}")
	public ResponseEntity<ApiResponse<UserPerformanceResponse.Detail>> detail(
		@Parameter(description = "공연 번호")
		@PathVariable Long performanceId
	) {
		return ResponseEntity.ok(ApiResponse.success(userPerformanceFacade.getDetail(performanceId)));
	}
}
