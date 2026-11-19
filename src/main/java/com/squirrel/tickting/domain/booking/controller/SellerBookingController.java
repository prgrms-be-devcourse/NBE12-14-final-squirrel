package com.squirrel.tickting.domain.booking.controller;

import com.squirrel.tickting.domain.booking.dto.SellerBookingResponse;
import com.squirrel.tickting.domain.booking.facade.SellerBookingFacade;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
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

@Tag(name = "Seller - Booking", description = "판매자의 예매 조회 API")
@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerBookingController {

	/** 기본 크기와 최대 크기를 같게 둔다. 더 크게 요청해도 에러 없이 이 값으로 줄어든다. */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final SellerBookingFacade sellerBookingFacade;

	/** 사업체 번호는 토큰에서 꺼낸다. 회차가 내 사업체 것이 아니면 없는 회차와 같은 404 다. */
	@Operation(summary = "회차별 예매 목록",
		description = "내 사업체 공연의 회차에 들어온 예매를 최신 예매순으로 준다(정렬은 고정, 한 번에 최대 20개). "
			+ "status 를 주지 않으면 남은 표가 있는 예매(PAID, PARTIAL_CANCELED)만 나오고, 전체 취소·결제 대기·만료는 status 로 따로 본다. "
			+ "receiveType 으로 배송·현장 수령만 고를 수 있고, bookingNo 는 예매 번호 정확 일치만 찾는다(현장 확인용). "
			+ "금액은 수수료 없는 표값이다(남은 표와 취소한 표의 등급별 장수·값, 최종 = 남은 표값 합). "
			+ "주문자 이름·전화번호와 배송 정보(배송 예매만)는 대표·담당자 모두에게 그대로 준다. "
			+ "취소된 회차·공연, 작성 중 공연의 회차도 조회할 수 있고, 남의 회차·없는 회차는 404(PERFORMANCE008)다.")
	@GetMapping("/schedules/{scheduleId}/bookings")
	public ResponseEntity<ApiResponse<PageResponse<SellerBookingResponse.Item>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long scheduleId,
		@Parameter(description = "예매 상태 한 가지 (PENDING, PAID, PARTIAL_CANCELED, CANCELED, EXPIRED). 비우면 PAID·PARTIAL_CANCELED")
		@RequestParam(required = false) BookingStatus status,
		@Parameter(description = "수령 방법 (ONSITE, DELIVERY). 비우면 모두")
		@RequestParam(required = false) ReceiveMethod receiveType,
		@Parameter(description = "예매 번호. 정확히 일치하는 것만 찾는다")
		@RequestParam(required = false) String bookingNo,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 정렬은 쿼리가 고정한다. 클라이언트가 보낸 sort 는 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		Page<SellerBookingResponse.Item> page = sellerBookingFacade.search(account.getBusinessId(), scheduleId,
			status, receiveType, bookingNo, pageRequest);
		PageResponse<SellerBookingResponse.Item> response = PageResponse.from(page);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
