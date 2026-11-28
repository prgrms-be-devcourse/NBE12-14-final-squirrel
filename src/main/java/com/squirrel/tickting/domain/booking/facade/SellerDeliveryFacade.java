package com.squirrel.tickting.domain.booking.facade;

import com.squirrel.tickting.domain.booking.dto.BookingRequest;
import com.squirrel.tickting.domain.booking.dto.SellerBookingResponse;
import com.squirrel.tickting.domain.booking.service.SellerDeliveryService;
import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 공연 단위 발송 처리(SLR-40·42). 공연 도메인(내 공연인가)과 예매 도메인(발송)을 엮는다.
 * "남의 공연이면 예매를 읽기 전에 404"라는 다른 도메인의 확인이 먼저 필요해서 파사드에 둔다.
 * 공연의 사업체는 바뀌지 않고 예매 쿼리도 사업체 번호로 거르므로, 두 트랜잭션을 하나로 묶지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SellerDeliveryFacade {

	private final SellerPerformanceService sellerPerformanceService;
	private final SellerDeliveryService sellerDeliveryService;

	public List<SellerBookingResponse.DeliveryTarget> getTargets(Long businessId, Long performanceId, Long scheduleId,
		boolean shipped) {
		sellerPerformanceService.getMinePerformance(businessId, performanceId);
		return sellerDeliveryService.getTargets(businessId, performanceId, scheduleId, shipped);
	}

	/** 요청만 보는 검사를 공연 조회보다 먼저 한다. */
	public SellerBookingResponse.ShipmentResult shipAll(Long businessId, Long performanceId,
		BookingRequest.TrackingBulk request) {
		sellerDeliveryService.validate(request);
		sellerPerformanceService.getMinePerformance(businessId, performanceId);
		return sellerDeliveryService.shipAll(businessId, performanceId, request);
	}
}
