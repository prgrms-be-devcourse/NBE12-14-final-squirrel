package com.squirrel.tickting.domain.booking.controller;

import com.squirrel.tickting.domain.booking.dto.BookingRequest;
import com.squirrel.tickting.domain.booking.dto.BookingResponse;
import com.squirrel.tickting.domain.booking.facade.BookingCancelFacade;
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
	private final BookingCancelFacade bookingCancelFacade;

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

	/** 남의 예매는 없는 예매와 같은 404 다. 예매자 번호를 조회 조건에 넣어서 거른다. */
	@Operation(summary = "예매 취소",
		description = "결제 완료된 예매의 좌석을 취소하고 환불한다. bookingSeatIds 를 비우면 남은 좌석 전부를 취소한다. "
			+ "토스 환불이 실패해도 좌석 취소는 끝나고 refund.status 가 FAILED 로 온다. (같은 멱등키로 다시 환불한다)")
	@PostMapping("/bookings/{bookingId}/cancel")
	public ResponseEntity<ApiResponse<BookingResponse.Canceled>> cancelBooking(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long bookingId,
		// 전체 취소는 본문 없이 보내도 되게 한다. (명세 예시는 {} 이지만 본문을 아예 빼는 클라이언트도 막지 않는다)
		@Valid @RequestBody(required = false) BookingRequest.Cancel request
	) {
		return ResponseEntity.ok(ApiResponse.success(bookingCancelFacade.cancel(account.getId(), bookingId, request)));
	}
}
