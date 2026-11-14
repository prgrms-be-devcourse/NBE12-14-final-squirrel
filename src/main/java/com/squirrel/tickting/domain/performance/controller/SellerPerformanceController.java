package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.facade.SellerPerformanceFacade;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller - Performance", description = "판매자의 내 공연 API")
@RestController
@RequestMapping("/api/v1/seller/performances")
@RequiredArgsConstructor
public class SellerPerformanceController {

	/** 기본 크기와 최대 크기를 같게 둔다. 더 크게 요청해도 에러 없이 이 값으로 줄어든다. (공통 설정의 최대는 50) */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final SellerPerformanceFacade sellerPerformanceFacade;

	/** 사업체 번호를 경로나 쿼리로 받지 않고 토큰에서 꺼낸다. 받으면 남의 사업체 번호를 넣어 볼 수 있기 때문이다. */
	@Operation(summary = "내 공연 목록",
		description = "로그인한 판매자 사업체의 공연 목록이다. 상태를 주지 않으면 취소된 공연까지 모두 나온다. "
			+ "최신 등록이 먼저 나오고(정렬은 고정) 한 번에 최대 20개까지 준다. 판매자 토큰만 쓸 수 있다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<SellerPerformanceResponse.Summary>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "공연 상태. 비우면 전체")
		@RequestParam(required = false) PerformanceStatus status,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 클라이언트가 보낸 정렬(sort)은 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		PageResponse<SellerPerformanceResponse.Summary> response = PageResponse.from(
			sellerPerformanceFacade.getList(account.getBusinessId(), status, pageRequest));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "내 공연 상세",
		description = "로그인한 판매자 사업체의 공연 상세다. 다른 사업체의 공연이거나 없는 공연은 모두 404 이다.")
	@GetMapping("/{performanceId}")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.Detail>> detail(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId
	) {
		SellerPerformanceResponse.Detail response = sellerPerformanceFacade.getDetail(account.getBusinessId(),
			performanceId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
