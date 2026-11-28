package com.squirrel.tickting.domain.admin.controller;

import com.squirrel.tickting.domain.admin.dto.AdminInquiryRequest;
import com.squirrel.tickting.domain.admin.dto.AdminInquiryResponse;
import com.squirrel.tickting.domain.inquiry.service.InquiryService;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 문의 조회·답변. 예매자·판매자 문의가 한 테이블이라 한 목록에서 본다. 로직은 문의 도메인의 서비스가 맡는다. */
@Tag(name = "Admin - Inquiry", description = "관리자의 문의 조회·답변 API")
@RestController
@RequestMapping("/api/v1/admin/inquiries")
@RequiredArgsConstructor
public class AdminInquiryController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final InquiryService inquiryService;

	@Operation(summary = "문의 목록",
		description = "예매자·판매자 문의를 최신 작성순으로 준다(정렬은 고정, 한 번에 최대 20개). 작성자 종류·답변 상태로 거를 수 있고 "
			+ "비우면 전체다. 기본은 삭제하지 않은 문의이고, deleted=true 면 작성자가 삭제한 문의만 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<AdminInquiryResponse.Summary>>> list(
		@Parameter(description = "작성자 종류 (USER / SELLER). 비우면 전체")
		@RequestParam(required = false) AccountType authorType,
		@Parameter(description = "답변 상태 (WAITING / ANSWERED). 비우면 전체")
		@RequestParam(required = false) InquiryStatus status,
		@Parameter(description = "true 면 삭제된 문의만. 기본 false (삭제된 문의 제외)")
		@RequestParam(defaultValue = "false") boolean deleted,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 클라이언트가 보낸 정렬(sort)은 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
		var page = inquiryService.searchForAdmin(authorType, status, deleted, pageRequest)
			.map(AdminInquiryResponse.Summary::from);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

	@Operation(summary = "문의 상세",
		description = "문의 하나와 작성·수정·삭제·답변 이력을 준다. 작성자가 삭제한 문의도 볼 수 있다. 없는 문의는 404 다.")
	@GetMapping("/{inquiryId}")
	public ResponseEntity<ApiResponse<AdminInquiryResponse.Detail>> detail(
		@Parameter(description = "문의 번호")
		@PathVariable Long inquiryId
	) {
		AdminInquiryResponse.Detail response = inquiryService.getForAdmin(inquiryId, AdminInquiryResponse.Detail::of);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "문의 답변",
		description = "답변 대기인 문의에 답변한다. 답변은 문의 하나에 하나뿐이고 고치거나 지울 수 없다. "
			+ "이미 답변했거나 그 사이 작성자가 지운 문의는 409, 없거나 삭제된 문의는 404 다.")
	@PostMapping("/{inquiryId}/answer")
	public ResponseEntity<ApiResponse<AdminInquiryResponse.Detail>> answer(
		@AuthenticationPrincipal CustomUserDetails admin,
		@Parameter(description = "문의 번호")
		@PathVariable Long inquiryId,
		@Valid @RequestBody AdminInquiryRequest.Answer request
	) {
		AdminInquiryResponse.Detail response = inquiryService.answer(admin.getId(), inquiryId, request.answer(),
			AdminInquiryResponse.Detail::of);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
