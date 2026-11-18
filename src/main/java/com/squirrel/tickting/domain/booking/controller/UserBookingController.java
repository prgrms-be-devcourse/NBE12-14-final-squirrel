package com.squirrel.tickting.domain.booking.controller;

import com.squirrel.tickting.domain.booking.dto.UserBookingResponse;
import com.squirrel.tickting.domain.booking.facade.UserBookingFacade;
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

	/** 공연 제목·회차 일시를 공연 서비스에서 받아 붙여야 해서 파사드를 부른다. */
	private final UserBookingFacade userBookingFacade;

	@Operation(summary = "내 예매 목록",
		description = "로그인한 예매자의 예매를 최신 예매순으로 준다. 상태(status)를 주지 않으면 시간 초과(EXPIRED)까지 모두 나온다. "
			+ "매수와 금액은 처음 예매 기준이다. 한 번에 최대 20개까지 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<UserBookingResponse.Summary>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "예매 상태. 비우면 전체")
		@RequestParam(required = false) BookingStatus status,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 정렬은 서비스가 최신 예매순으로 고정한다. 번호와 크기만 넘긴다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		Page<UserBookingResponse.Summary> page = userBookingFacade.getMyBookings(account.getId(), status, pageRequest);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}
}
