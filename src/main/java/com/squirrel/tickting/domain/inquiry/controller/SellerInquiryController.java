package com.squirrel.tickting.domain.inquiry.controller;

import com.squirrel.tickting.domain.inquiry.dto.SellerInquiryRequest;
import com.squirrel.tickting.domain.inquiry.dto.SellerInquiryResponse;
import com.squirrel.tickting.domain.inquiry.dto.UserInquiryResponse;
import com.squirrel.tickting.domain.inquiry.facade.SellerInquiryFacade;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자 문의. 대표·담당자 모두 쓸 수 있다. (SecurityConfig 의 /api/v1/seller/**)
 * 같은 사업체의 문의는 누가 썼든 함께 본다. 작성자·사업체 번호는 요청이 아니라 세션에서 꺼낸다.
 */
@Tag(name = "SellerInquiry", description = "판매자 문의 API")
@RestController
@RequestMapping("/api/v1/seller/inquiries")
@RequiredArgsConstructor
public class SellerInquiryController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final SellerInquiryFacade sellerInquiryFacade;
	private final InquiryService inquiryService;

	@Operation(summary = "문의 유형 목록", description = "판매자가 고를 수 있는 문의 유형을 준다. 예매자 유형은 나오지 않는다.")
	@GetMapping("/categories")
	public ResponseEntity<ApiResponse<List<UserInquiryResponse.Category>>> categories() {
		return ResponseEntity.ok(ApiResponse.success(inquiryService.getCategories(AccountType.SELLER)));
	}

	@Operation(summary = "문의 작성",
		description = "답변 대기 상태로 문의를 만든다. 같은 사업체의 대표·담당자가 함께 본다. 사진은 3장까지.")
	@PostMapping
	public ResponseEntity<ApiResponse<UserInquiryResponse.Created>> create(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody SellerInquiryRequest.Create request
	) {
		UserInquiryResponse.Created created = sellerInquiryFacade.create(account.getId(), account.getBusinessId(),
			request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@Operation(summary = "사업체 문의 목록",
		description = "우리 사업체의 문의(대표·담당자 모두)를 최신 작성순으로 준다. 삭제한 문의는 나오지 않는다. 한 번에 최대 20개까지.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<SellerInquiryResponse.Summary>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 정렬은 서비스가 최신 작성순으로 고정한다. 번호와 크기만 넘긴다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
		return ResponseEntity.ok(ApiResponse.success(
			PageResponse.from(inquiryService.getBusinessInquiries(account.getBusinessId(), pageRequest))));
	}

	@Operation(summary = "사업체 문의 상세", description = "다른 사업체의 문의·삭제한 문의·없는 문의는 404 다.")
	@GetMapping("/{inquiryId}")
	public ResponseEntity<ApiResponse<SellerInquiryResponse.Detail>> detail(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "문의 번호")
		@PathVariable Long inquiryId
	) {
		return ResponseEntity.ok(ApiResponse.success(
			inquiryService.getBusinessInquiry(account.getBusinessId(), inquiryId)));
	}
}
