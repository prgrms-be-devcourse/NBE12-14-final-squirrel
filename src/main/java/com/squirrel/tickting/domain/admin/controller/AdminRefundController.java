package com.squirrel.tickting.domain.admin.controller;

import com.squirrel.tickting.domain.admin.dto.AdminRefundResponse;
import com.squirrel.tickting.domain.admin.facade.AdminRefundFacade;
import com.squirrel.tickting.domain.payment.service.RefundService;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
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
import org.springframework.web.bind.annotation.PostMapping;
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
	/** 재처리는 감사 로그와 함께 처리하므로 파사드로 부른다. 조회는 서비스를 바로 부른다. */
	private final AdminRefundFacade adminRefundFacade;

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

	@Operation(summary = "환불 수동 재처리 (ADM-06)",
		description = "실패한 환불을 처음과 같은 멱등키로 토스에 다시 보내고 감사 로그를 남긴다. 토스가 또 실패해도 200 이고 "
			+ "status 가 FAILED 다. 없으면 404, 처리 중이거나 실패가 아니거나 요청한 지 15일이 지났으면 409.")
	@PostMapping("/{refundId}/retry")
	public ResponseEntity<ApiResponse<AdminRefundResponse.Retried>> retry(
		@AuthenticationPrincipal CustomUserDetails admin,
		@PathVariable Long refundId
	) {
		return ResponseEntity.ok(ApiResponse.success(adminRefundFacade.retry(admin.getId(), refundId)));
	}
}
