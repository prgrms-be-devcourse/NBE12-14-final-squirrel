package com.squirrel.tickting.domain.payment.controller;

import com.squirrel.tickting.domain.payment.dto.PaymentRequest;
import com.squirrel.tickting.domain.payment.dto.PaymentResponse;
import com.squirrel.tickting.domain.payment.facade.PaymentFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Payment", description = "예매자 결제 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentController {

	private final PaymentFacade paymentFacade;

	/** 예매자 번호는 요청이 아니라 토큰에서 꺼낸다. 남의 예매 번호를 넣어도 내 예매가 아니면 404 다. */
	@Operation(summary = "결제 준비",
		description = "수령 방법·배송 정보를 확정하고 결제 준비(READY) 건을 만든다. 이미 준비 건이 있으면 같은 주문 ID 를 다시 돌려준다.")
	@PostMapping("/bookings/{bookingId}/payments")
	public ResponseEntity<ApiResponse<PaymentResponse.Prepared>> prepare(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long bookingId,
		@Valid @RequestBody PaymentRequest.Prepare request
	) {
		PaymentResponse.Prepared prepared = paymentFacade.prepare(account.getId(), bookingId, request);
		return ResponseEntity.ok(ApiResponse.success(prepared));
	}

	/** 결제 금액은 요청으로 받은 값을 믿지 않는다. 서버가 저장한 금액과 비교만 하고 토스에도 저장한 금액을 보낸다. */
	@Operation(summary = "결제 승인",
		description = "토스 결제창이 성공한 뒤 paymentKey·orderId·amount 로 호출한다. 검증 → 토스 승인 → 예매 결제 완료(PAID)·좌석 판매 완료(SOLD)."
			+ " 토스 통신 오류로 승인 여부를 모르면 502 이고, 결제는 승인 중으로 남아 서버가 확정한다.")
	@PostMapping("/payments/confirm")
	public ResponseEntity<ApiResponse<PaymentResponse.Confirmed>> confirm(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody PaymentRequest.Confirm request
	) {
		PaymentResponse.Confirmed confirmed = paymentFacade.confirm(account.getId(), request);
		return ResponseEntity.ok(ApiResponse.success(confirmed));
	}
}
