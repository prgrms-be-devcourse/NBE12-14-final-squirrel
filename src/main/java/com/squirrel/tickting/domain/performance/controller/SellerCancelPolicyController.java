package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.dto.CancelPolicyResponse;
import com.squirrel.tickting.domain.performance.service.CancelPolicyService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller - Cancel Policy", description = "판매자의 취소 규정 API")
@RestController
@RequestMapping("/api/v1/seller/cancel-policies")
@RequiredArgsConstructor
public class SellerCancelPolicyController {

	/** 판매자 목록(공연장, 내 공연)과 같이 기본 크기와 최대 크기를 같게 둔다. 더 크게 요청해도 에러 없이 이 값으로 줄어든다. */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final CancelPolicyService cancelPolicyService;

	/** 사업체 번호를 경로나 쿼리로 받지 않고 토큰에서 꺼낸다. 받으면 남의 사업체 번호를 넣어 볼 수 있기 때문이다. */
	@Operation(summary = "취소 규정 목록",
		description = "서비스 기본 규정과 로그인한 판매자 사업체의 규정 목록이다. 기본 규정이 첫 페이지 맨 앞에 나오고 "
			+ "내 규정은 최신순이다(정렬은 고정). 한 번에 최대 20개까지 준다. 판매자 토큰만 쓸 수 있다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<CancelPolicyResponse.Summary>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 클라이언트가 보낸 정렬(sort)은 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		var page = cancelPolicyService.searchVisible(account.getBusinessId(), pageRequest)
			.map(CancelPolicyResponse.Summary::from);
		PageResponse<CancelPolicyResponse.Summary> response = PageResponse.from(page);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
