package com.squirrel.tickting.domain.admin.controller;

import com.squirrel.tickting.domain.admin.dto.AdminRefundResponse;
import com.squirrel.tickting.domain.payment.service.RefundService;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
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

@Tag(name = "Admin - Refund", description = "관리자의 환불 관리 API")
@RestController
@RequestMapping("/api/v1/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final RefundService refundService;

	@Operation(summary = "환불 실패 목록 (ADM-05)",
		description = "기본은 자동 재시도를 다 쓰고도 실패한 환불, 즉 사람이 처리해야 하는 것만 준다. "
			+ "exhaustedOnly=false 면 재시도 중인 것까지 보인다. 오래 기다린 요청부터 나오고(정렬은 고정) 한 번에 최대 20개까지 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<AdminRefundResponse.Summary>>> list(
		@Parameter(description = "상태. 기본 FAILED")
		@RequestParam(defaultValue = "FAILED") RefundStatus status,
		@Parameter(description = "자동 재시도를 다 쓴 것만 볼지. 기본 true")
		@RequestParam(defaultValue = "true") boolean exhaustedOnly,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 클라이언트가 보낸 정렬(sort)은 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		var page = refundService.searchForAdmin(status, exhaustedOnly, pageRequest)
			.map(AdminRefundResponse.Summary::from);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}
}
