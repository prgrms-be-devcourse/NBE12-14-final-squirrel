package com.squirrel.tickting.domain.booking.facade;

import com.squirrel.tickting.domain.booking.dto.UserBookingResponse;
import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.service.UserBookingService;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.service.UserPerformanceService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * 예매자의 예매 조회. 예매는 공연·회차를 번호로만 알고 있어서, 서비스끼리 부르지 않고
 * 이 파사드가 예매 서비스와 공연 서비스를 엮어 응답으로 만든다. 읽기만 해서 각 서비스의 읽기 전용 트랜잭션으로 충분하다.
 */
@Component
@RequiredArgsConstructor
public class UserBookingFacade {

	private final UserBookingService userBookingService;
	private final UserPerformanceService userPerformanceService;

	/**
	 * 내 예매 목록. 좌석 수·공연·회차는 예매마다 조회하지 않고, 한 페이지에 나온 번호 목록으로 한 번씩만 읽는다.
	 */
	public Page<UserBookingResponse.Summary> getMyBookings(Long userId, BookingStatus status, Pageable pageable) {
		Page<Booking> bookings = userBookingService.getMyBookings(userId, status, pageable);
		List<Booking> content = bookings.getContent();

		Map<Long, Integer> seatCounts = userBookingService.countSeats(content.stream().map(Booking::getId).toList());
		Map<Long, Performance> performances = userPerformanceService.getPerformances(
			content.stream().map(Booking::getPerformanceId).distinct().toList());
		Map<Long, PerformanceSchedule> schedules = userPerformanceService.getSchedules(
			content.stream().map(Booking::getScheduleId).distinct().toList());

		return bookings.map(booking -> UserBookingResponse.Summary.from(booking,
			seatCounts.getOrDefault(booking.getId(), 0),
			performances.get(booking.getPerformanceId()),
			schedules.get(booking.getScheduleId())));
	}
}
