package com.squirrel.tickting.domain.booking.facade;

import com.squirrel.tickting.domain.booking.dto.SellerBookingResponse;
import com.squirrel.tickting.domain.booking.service.SellerBookingService;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * 판매자의 회차별 예매 조회 흐름. 공연 도메인(내 회차인가)과 예매 도메인(목록)을 엮는다.
 * 파사드는 보통 쓰기가 걸리는 흐름에만 두지만, 여기는 응답에 값을 붙이는 조회가 아니라 "남의 회차면 예매를 읽기 전에 404"라는
 * 다른 도메인의 확인이 먼저 필요하다. 서비스끼리 부르지 않으려고 읽기 전용이어도 파사드에 둔다.
 * 읽기만 해서 락·트랜잭션 묶음이 없고, 각 서비스의 읽기 전용 트랜잭션이 따로 돈다.
 */
@Component
@RequiredArgsConstructor
public class SellerBookingFacade {

	private final SellerPerformanceService sellerPerformanceService;
	private final SellerBookingService sellerBookingService;

	/** 남의 회차와 없는 회차는 예매를 읽기 전에 같은 404 로 끝난다. */
	public Page<SellerBookingResponse.Item> search(Long businessId, Long scheduleId, BookingStatus status,
		ReceiveMethod receiveType, String bookingNo, Pageable pageable) {
		sellerPerformanceService.validateMySchedule(businessId, scheduleId);
		return sellerBookingService.search(scheduleId, status, receiveType, bookingNo, pageable);
	}
}
