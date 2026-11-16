package com.squirrel.tickting.domain.venue.controller;

import com.squirrel.tickting.domain.venue.dto.VenueResponse;
import com.squirrel.tickting.domain.venue.service.VenueService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller - Venue", description = "판매자의 공연장 조회 API")
@RestController
@RequestMapping("/api/v1/seller/venues")
@RequiredArgsConstructor
public class SellerVenueController {

	/** 기본 크기와 최대 크기를 같게 둔다. 더 크게 요청해도 에러 없이 이 값으로 줄어든다. (공통 설정의 최대는 50) */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final VenueService venueService;

	@Operation(summary = "공연장 목록",
		description = "공연 등록 때 고르는 사용 중인 공연장 목록이다. 이름순(같으면 번호순)으로 고정이고 한 번에 최대 20개까지 준다. "
			+ "지역은 정확히 같은 것만, 검색어는 공연장 이름에서 부분 일치로 찾는다. 판매자 토큰만 쓸 수 있다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<VenueResponse.Summary>>> list(
		@Parameter(description = "지역. 정확히 같은 것만. 비우면 전체", example = "서울")
		@RequestParam(required = false) String region,
		@Parameter(description = "공연장 이름 검색어. 비우면 전체")
		@RequestParam(required = false) String keyword,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 클라이언트가 보낸 정렬(sort)은 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		var page = venueService.searchActive(region, keyword, pageRequest)
			.map(VenueResponse.Summary::from);
		PageResponse<VenueResponse.Summary> response = PageResponse.from(page);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
