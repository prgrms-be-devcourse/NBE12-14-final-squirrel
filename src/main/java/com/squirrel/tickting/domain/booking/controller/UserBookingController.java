package com.squirrel.tickting.domain.booking.controller;

import com.squirrel.tickting.domain.booking.dto.BookingSearchRequest;
import com.squirrel.tickting.domain.booking.dto.UserBookingResponse;
import com.squirrel.tickting.domain.booking.service.BookingQueryService;
import com.squirrel.tickting.domain.booking.service.BookingScope;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예매자의 내 예매 조회. 예매자만 쓸 수 있다. (SecurityConfig 의 USER_ONLY_PATHS: /api/v1/bookings/**)
 * 범위는 토큰의 회원 번호로만 만든다. 요청으로 회원 번호를 받으면 남의 예매를 볼 수 있다.
 */
@Tag(name = "Booking", description = "예매자 예매 API")
@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class UserBookingController {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final BookingQueryService bookingQueryService;

	@Operation(summary = "내 예매 목록",
		description = "로그인한 예매자의 예매를 최신 예매순으로 준다(정렬은 고정, 한 번에 최대 20개). 공연 제목(부분 일치)·상태(여러 개)·"
			+ "예매한 날짜로 거를 수 있다. 시간 초과(EXPIRED)는 나오지 않고 그 상태를 고르면 400 이다. "
			+ "매수와 금액은 처음 예매 기준이다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<UserBookingResponse.Summary>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@ParameterObject @Valid @ModelAttribute BookingSearchRequest request,
		@ParameterObject @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 정렬은 서비스가 최신 예매순으로 고정한다. 번호와 크기만 넘긴다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(), Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		Page<UserBookingResponse.Summary> page = bookingQueryService.search(new BookingScope.OfUser(account.getId()),
			request, pageRequest, UserBookingResponse.Summary::from);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

	@Operation(summary = "내 예매 상세",
		description = "로그인한 예매자의 예매 하나를 준다. 목록과 달리 상태와 상관없이 준다. (결제 화면이 결제 대기 예매를 읽는다) "
			+ "남의 예매·없는 예매는 404 다.")
	@GetMapping("/{bookingId}")
	public ResponseEntity<ApiResponse<UserBookingResponse.Detail>> detail(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "예매 번호")
		@PathVariable Long bookingId
	) {
		UserBookingResponse.Detail response = bookingQueryService.getFullDetail(
			new BookingScope.OfUser(account.getId()), bookingId, (booking, detail) -> detail);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
