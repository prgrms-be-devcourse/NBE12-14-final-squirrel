package com.squirrel.tickting.domain.inquiry.controller;

import com.squirrel.tickting.domain.inquiry.dto.UserInquiryRequest;
import com.squirrel.tickting.domain.inquiry.dto.UserInquiryResponse;
import com.squirrel.tickting.domain.inquiry.facade.UserInquiryFacade;
import com.squirrel.tickting.domain.inquiry.service.InquiryService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 예매자 문의. 예매자만 쓸 수 있다. (SecurityConfig 의 USER_ONLY_PATHS: /api/v1/inquiries/**) */
@Tag(name = "Inquiry", description = "예매자 문의 API")
@RestController
@RequestMapping("/api/v1/inquiries")
@RequiredArgsConstructor
public class UserInquiryController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final UserInquiryFacade userInquiryFacade;
	private final InquiryService inquiryService;

	@Operation(summary = "문의 유형 목록", description = "예매자가 고를 수 있는 문의 유형을 준다. 판매자 유형은 나오지 않는다.")
	@GetMapping("/categories")
	public ResponseEntity<ApiResponse<List<UserInquiryResponse.Category>>> categories() {
		return ResponseEntity.ok(ApiResponse.success(inquiryService.getCategories(AccountType.USER)));
	}

	/** 작성자 번호는 요청이 아니라 토큰에서 꺼낸다. 요청으로 받으면 남의 이름으로 문의를 쓸 수 있다. */
	@Operation(summary = "문의 작성",
		description = "답변 대기 상태로 문의를 만든다. 예매는 고르지 않아도 되고, 고르면 내 예매여야 한다. 사진은 3장까지.")
	@PostMapping
	public ResponseEntity<ApiResponse<UserInquiryResponse.Created>> create(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody UserInquiryRequest.Create request
	) {
		UserInquiryResponse.Created created = userInquiryFacade.create(account.getId(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@Operation(summary = "내 문의 목록", description = "최신 작성순으로 준다. 삭제한 문의는 나오지 않는다. 한 번에 최대 20개까지.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<UserInquiryResponse.Summary>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 정렬은 서비스가 최신 작성순으로 고정한다. 번호와 크기만 넘긴다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
		return ResponseEntity.ok(ApiResponse.success(
			PageResponse.from(inquiryService.getMyInquiries(account.getId(), pageRequest))));
	}

	@Operation(summary = "내 문의 상세", description = "남의 문의·삭제한 문의·없는 문의는 404 다.")
	@GetMapping("/{inquiryId}")
	public ResponseEntity<ApiResponse<UserInquiryResponse.Detail>> detail(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "문의 번호")
		@PathVariable Long inquiryId
	) {
		return ResponseEntity.ok(ApiResponse.success(inquiryService.getMyInquiry(account.getId(), inquiryId)));
	}

	@Operation(summary = "문의 수정",
		description = "답변 대기일 때만 내용을 통째로 바꾼다. 답변이 끝났으면 409, 남의 문의·삭제한 문의는 404 다.")
	@PutMapping("/{inquiryId}")
	public ResponseEntity<ApiResponse<UserInquiryResponse.Detail>> update(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "문의 번호")
		@PathVariable Long inquiryId,
		@Valid @RequestBody UserInquiryRequest.Update request
	) {
		return ResponseEntity.ok(ApiResponse.success(userInquiryFacade.update(account.getId(), inquiryId, request)));
	}

	@Operation(summary = "문의 삭제",
		description = "답변 대기일 때만 지운다. 목록·상세에서 사라지고 이력은 남는다. 답변이 끝났으면 409 다.")
	@DeleteMapping("/{inquiryId}")
	public ResponseEntity<ApiResponse<Void>> delete(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "문의 번호")
		@PathVariable Long inquiryId
	) {
		inquiryService.deleteForUser(account.getId(), inquiryId);
		return ResponseEntity.ok(ApiResponse.noContentSuccess());
	}
}
