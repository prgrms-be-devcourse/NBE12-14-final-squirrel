package com.squirrel.tickting.domain.booking.controller;

import com.squirrel.tickting.domain.booking.dto.BookingRequest;
import com.squirrel.tickting.domain.booking.dto.BookingResponse;
import com.squirrel.tickting.domain.booking.facade.BookingFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Booking", description = "예매자 예매 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BookingController {

	private final BookingFacade bookingFacade;

	/** 예매자 번호는 요청이 아니라 토큰에서 꺼낸다. 요청으로 받으면 남의 이름으로 좌석을 잡을 수 있다. */
	@Operation(summary = "좌석 점유·예매 생성",
		description = "좌석을 점유하고 결제 대기(PENDING) 예매를 만든다. 하나라도 점유할 수 없으면 전체를 거절한다.")
	@PostMapping("/schedules/{scheduleId}/bookings")
	public ResponseEntity<ApiResponse<BookingResponse.Created>> createBooking(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long scheduleId,
		@Valid @RequestBody BookingRequest.Create request
	) {
		BookingResponse.Created created = bookingFacade.createBooking(account.getId(), scheduleId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}
}
