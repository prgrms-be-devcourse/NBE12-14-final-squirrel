package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.dto.PerformanceResponse;
import com.squirrel.tickting.domain.performance.facade.PerformanceFacade;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.PerformanceSortType;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 예매자 화면의 공연 조회. 로그인 없이 볼 수 있다. (SecurityConfig 의 PUBLIC_READ_PATHS) */
@Tag(name = "Performance", description = "예매자 공연 조회 API")
@RestController
@RequestMapping("/api/v1/performances")
@RequiredArgsConstructor
public class PerformanceController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	/** 공연장 이름·지역을 공연장 서비스에서 받아 붙여야 해서 파사드를 부른다. */
	private final PerformanceFacade performanceFacade;

	@Operation(summary = "공연 목록",
		description = "공개된 공연을 보여 준다. 장르를 주지 않으면 전체, 주면 그 장르만 나온다. "
			+ "정렬은 IMMINENT(공연 임박순, 기본) · LATEST(최신 등록순) · TITLE(이름순)이고, 한 번에 최대 20개까지 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<PerformanceResponse.Summary>>> list(
		@Parameter(description = "장르. 비우면 전체")
		@RequestParam(required = false) Genre genre,
		@Parameter(description = "정렬. 기본 IMMINENT")
		@RequestParam(defaultValue = "IMMINENT") PerformanceSortType sort,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 정렬은 sort 값으로만 정한다. Pageable 이 같은 이름의 파라미터를 정렬 컬럼으로 읽어도 쓰지 않고 번호와 크기만 넘긴다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		Page<PerformanceResponse.Summary> page = performanceFacade.getList(genre, sort, pageRequest);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}
}
