package com.squirrel.tickting.domain.booking.controller;

import com.squirrel.tickting.domain.booking.dto.UserBookingResponse;
import com.squirrel.tickting.domain.booking.service.UserBookingService;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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

/** 예매자의 내 예매 조회. 예매자만 쓸 수 있다. (SecurityConfig 의 USER_ONLY_PATHS: /api/v1/bookings/**) */
@Tag(name = "Booking", description = "예매자 예매 API")
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class UserBookingController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final UserBookingService userBookingService;

	@Operation(summary = "내 예매 목록",
		description = "로그인한 예매자의 예매를 최신 예매순으로 준다. 시간 초과(EXPIRED)는 나오지 않고, "
			+ "그 상태를 status 로 보내면 400 이다. 매수와 금액은 처음 예매 기준이다. 한 번에 최대 20개까지 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<UserBookingResponse.Summary>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "예매 상태 (PENDING, PAID, PARTIAL_CANCELED, CANCELED). 비우면 넷 모두")
		@RequestParam(required = false) BookingStatus status,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 정렬은 서비스가 최신 예매순으로 고정한다. 번호와 크기만 넘긴다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		Page<UserBookingResponse.Summary> page = userBookingService.getMyBookings(account.getId(), status, pageRequest);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

	@Operation(summary = "내 예매 상세",
		description = "로그인한 예매자의 예매 하나를 준다. 목록과 달리 상태와 상관없이 준다. (결제 화면이 결제 대기 예매를 읽는다) "
			+ "남의 예매·없는 예매는 404 다. 환불 금액·취소 가능 여부·지금 수수료율은 예매 취소가 들어올 때까지 null 이다.")
	@GetMapping("/{bookingId}")
	public ResponseEntity<ApiResponse<UserBookingResponse.Detail>> detail(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "예매 번호")
		@PathVariable Long bookingId
	) {
		UserBookingResponse.Detail response = userBookingService.getMyBooking(account.getId(), bookingId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
