package com.squirrel.tickting.domain.booking.controller;

import com.squirrel.tickting.domain.booking.dto.BookingRequest;
import com.squirrel.tickting.domain.booking.dto.SellerBookingResponse;
import com.squirrel.tickting.domain.booking.facade.SellerBookingFacade;
import com.squirrel.tickting.domain.booking.facade.SellerDeliveryFacade;
import com.squirrel.tickting.domain.booking.service.BookingQueryService;
import com.squirrel.tickting.domain.booking.service.BookingScope;
import com.squirrel.tickting.domain.booking.service.SellerDeliveryService;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller - Booking", description = "판매자의 예매 조회·발송 처리 API")
@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerBookingController {

	/** 기본 크기와 최대 크기를 같게 둔다. 더 크게 요청해도 에러 없이 이 값으로 줄어든다. */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final SellerBookingFacade sellerBookingFacade;
	private final BookingQueryService bookingQueryService;
	private final SellerDeliveryFacade sellerDeliveryFacade;
	private final SellerDeliveryService sellerDeliveryService;

	/** 범위는 토큰의 사업체 번호로만 만든다. 요청으로 사업체 번호를 받으면 남의 사업체 예매를 볼 수 있다. */
	@Operation(summary = "내 사업체 예매 목록",
		description = "내 사업체의 모든 공연에 들어온 예매를 최신 예매순으로 준다(정렬은 고정, 한 번에 최대 20개). "
			+ "공연 제목(부분 일치)·상태(여러 개)·예매한 날짜로 거를 수 있다. "
			+ "상태를 비우면 남은 표가 있는 예매(PAID, PARTIAL_CANCELED)만 나온다. 시작 날짜가 끝 날짜보다 늦으면 400 이다. "
			+ "금액은 수수료 없는 표값이고, 주문자 이름·전화번호와 배송 정보는 대표·담당자 모두에게 그대로 준다.")
	@GetMapping("/bookings")
	public ResponseEntity<ApiResponse<PageResponse<SellerBookingResponse.Summary>>> search(
		@AuthenticationPrincipal CustomUserDetails account,
		@ParameterObject @Valid @ModelAttribute BookingRequest.Search request,
		@ParameterObject @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));
		Page<SellerBookingResponse.Summary> page = bookingQueryService.search(
			new BookingScope.OfBusiness(account.getBusinessId()), request, pageRequest,
			SellerBookingResponse.Summary::from);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page)));
	}

	@Operation(summary = "내 사업체 예매 상세",
		description = "내 사업체 공연의 예매 하나를 상태와 상관없이 준다. 목록 항목과 같은 모양이다. "
			+ "다른 사업체 공연의 예매·없는 예매는 404 다.")
	@GetMapping("/bookings/{bookingId}")
	public ResponseEntity<ApiResponse<SellerBookingResponse.Summary>> detail(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long bookingId
	) {
		SellerBookingResponse.Summary response = bookingQueryService.getDetail(
			new BookingScope.OfBusiness(account.getBusinessId()), bookingId, SellerBookingResponse.Summary::from);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "배송 대상 목록",
		description = "내 사업체 공연에서 발송할 수 있는 예매(배송, 결제 완료·일부 취소)를 페이지 없이 모두 준다. 파일로 내려받는 용도다. "
			+ "shipped=false(기본)면 발송 전, true 면 발송한 예매만 준다. 회차 시작 시각, 예매 시각 순이다. "
			+ "남의 공연·없는 공연은 404(PERFORMANCE000)다.")
	@GetMapping("/performances/{performanceId}/deliveries")
	public ResponseEntity<ApiResponse<List<SellerBookingResponse.DeliveryTarget>>> deliveries(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId,
		@Parameter(description = "이 회차만. 비우면 모든 회차")
		@RequestParam(required = false) Long scheduleId,
		@RequestParam(defaultValue = "false") boolean shipped
	) {
		List<SellerBookingResponse.DeliveryTarget> response = sellerDeliveryFacade.getTargets(
			account.getBusinessId(), performanceId, scheduleId, shipped);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "송장 일괄 입력",
		description = "예매 번호로 찾아 택배사·송장번호를 한꺼번에 넣는다. 형식 오류·모르는 택배사·예매 번호 중복·건수 초과는 "
			+ "전체를 400 으로 거절하고, 없는 예매·현장 수령·취소된 예매는 그 행만 실패로 돌려준다.")
	@PutMapping("/performances/{performanceId}/deliveries")
	public ResponseEntity<ApiResponse<SellerBookingResponse.ShipmentResult>> shipAll(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId,
		@Valid @RequestBody BookingRequest.TrackingBulk request
	) {
		SellerBookingResponse.ShipmentResult response = sellerDeliveryFacade.shipAll(account.getBusinessId(),
			performanceId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "송장 입력 (단건)",
		description = "예매 하나의 택배사·송장번호를 넣거나 고친다. 발송 일시는 처음 넣은 시각을 유지한다. "
			+ "남의 예매 404(BOOKING006), 현장 수령 409(BOOKING014), 발송할 수 없는 상태 409(BOOKING015).")
	@PutMapping("/bookings/{bookingId}/delivery")
	public ResponseEntity<ApiResponse<SellerBookingResponse.Summary>> ship(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long bookingId,
		@Valid @RequestBody BookingRequest.Tracking request
	) {
		SellerBookingResponse.Summary response = sellerDeliveryService.ship(account.getBusinessId(), bookingId,
			request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/**
	 * 사업체 번호는 토큰에서 꺼낸다. 회차가 내 사업체 것이 아니면 없는 회차와 같은 404 다.
	 *
	 * @deprecated 역할별 예매 조회를 BookingQueryService 로 모았다. 새 화면은 내 사업체 예매 목록(GET /seller/bookings)을 쓴다.
	 * 이미 쓰는 화면이 있어서 지우지 않고 남겨 둔다.
	 */
	@Deprecated(since = "2026-11-25")
	@Operation(summary = "회차별 예매 목록 (사용 중단 예정)",
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
