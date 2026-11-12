package com.squirrel.tickting.domain.seller.controller;

import com.squirrel.tickting.domain.seller.dto.SellerBusinessResponse;
import com.squirrel.tickting.domain.seller.service.SellerBusinessService;
import com.squirrel.tickting.domain.seller.type.SellerBusinessSearchStatus;
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

@Tag(name = "Admin - Seller Business", description = "관리자의 판매자 사업체 관리 API")
@RestController
@RequestMapping("/api/v1/admin/seller-businesses")
@RequiredArgsConstructor
public class AdminSellerBusinessController {

	/** 한 번에 너무 많이 긁어가지 못하게 이 API 는 20개까지만 준다. (공통 설정은 50) */
	private static final int DEFAULT_PAGE_SIZE = 10;
	private static final int MAX_PAGE_SIZE = 20;

	private final SellerBusinessService sellerBusinessService;

	@Operation(summary = "사업체 목록·검색",
		description = "상호·대표자명·사업자번호에서 검색어를 부분 일치로 찾는다. 상태를 주지 않으면 승인 대기만 보이고, "
			+ "ALL 이면 모든 상태가 보인다. 최신 신청이 먼저 나오고(정렬은 고정) 한 번에 최대 20개까지 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<SellerBusinessResponse.Summary>>> list(
		@Parameter(description = "검색어. 비우면 전체")
		@RequestParam(required = false) String keyword,
		@Parameter(description = "상태. 기본 PENDING, ALL 은 전체")
		@RequestParam(defaultValue = "PENDING") SellerBusinessSearchStatus status,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 클라이언트가 보낸 정렬(sort)은 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		var page = sellerBusinessService.search(status, keyword, pageRequest)
			.map(SellerBusinessResponse.Summary::from);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}
}
