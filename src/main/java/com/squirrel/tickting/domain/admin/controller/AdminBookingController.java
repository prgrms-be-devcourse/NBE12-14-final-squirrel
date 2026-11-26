package com.squirrel.tickting.domain.admin.controller;

import com.squirrel.tickting.domain.admin.dto.AdminBookingResponse;
import com.squirrel.tickting.domain.booking.dto.BookingRequest;
import com.squirrel.tickting.domain.booking.service.BookingQueryService;
import com.squirrel.tickting.domain.booking.service.BookingScope;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자의 전체 예매 조회. 범위는 전체라 사업체·예매자 조건 없이 BookingQueryService 를 부른다. */
@Tag(name = "Admin - Booking", description = "관리자의 예매 조회 API")
@RestController
@RequestMapping("/api/v1/admin/bookings")
@RequiredArgsConstructor
public class AdminBookingController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final BookingQueryService bookingQueryService;

	@Operation(summary = "전체 예매 목록",
		description = "모든 예매를 최신 예매순으로 준다(정렬은 고정, 한 번에 최대 20개). 공연 제목(부분 일치)·상태(여러 개)·"
			+ "예매한 날짜로 거를 수 있다. 상태를 비우면 만료를 포함한 모든 상태다. 시작 날짜가 끝 날짜보다 늦으면 400 이다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<AdminBookingResponse.Summary>>> list(
		@ParameterObject @Valid @ModelAttribute BookingRequest.Search request,
		@ParameterObject @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
		var page = bookingQueryService.search(new BookingScope.All(), request, pageRequest,
			AdminBookingResponse.Summary::from);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

	@Operation(summary = "예매 상세",
		description = "예매 하나를 상태와 상관없이 준다. 예매자 상세와 같은 내용(결제·환불·취소 규정)에 예매자·사업체 번호를 더했다. "
			+ "없는 예매는 404 다.")
	@GetMapping("/{bookingId}")
	public ResponseEntity<ApiResponse<AdminBookingResponse.Detail>> detail(@PathVariable Long bookingId) {
		AdminBookingResponse.Detail response = bookingQueryService.getFullDetail(new BookingScope.All(), bookingId,
			AdminBookingResponse.Detail::of);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
