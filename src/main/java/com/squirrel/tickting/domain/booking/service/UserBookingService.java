package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.repository.UserBookingRepository;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매자(User) 화면의 예매 조회. 예매 도메인을 예매 생성·취소(C)와 판매자 조회(A)가 함께 쓰므로,
 * 같은 파일을 동시에 고치지 않게 역할 이름을 붙여 나눈다. 공연 제목·회차 일시는 공연 도메인이라 파사드가 붙인다.
 */
@Service
@RequiredArgsConstructor
public class UserBookingService {

	/** 최근 예매가 먼저. 같은 시각이면 번호가 큰 쪽이 먼저라 페이지를 넘겨도 순서가 바뀌지 않는다. */
	private static final Sort LATEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

	private final UserBookingRepository userBookingRepository;

	/**
	 * 한 예매자의 예매 목록. 상태를 주지 않으면(null) 결제하지 않고 시간이 지난 예매(EXPIRED)까지 모두 나온다.
	 * pageable 은 페이지 번호와 크기만 쓰고 정렬은 최신 예매순으로 고정한다.
	 */
	@Transactional(readOnly = true)
	public Page<Booking> getMyBookings(Long userId, BookingStatus status, Pageable pageable) {
		Collection<BookingStatus> statuses = status == null ? EnumSet.allOf(BookingStatus.class) : EnumSet.of(status);
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
		return userBookingRepository.findMine(userId, statuses, sorted);
	}

	/** 예매 번호 → 처음 예매한 좌석 수(취소된 좌석 포함). 목록 한 페이지의 예매를 한 번에 센다. */
	@Transactional(readOnly = true)
	public Map<Long, Integer> countSeats(Collection<Long> bookingIds) {
		if (bookingIds.isEmpty()) {
			return Map.of();
		}
		return userBookingRepository.countSeats(bookingIds).stream()
			.collect(Collectors.toMap(
				row -> row.get("bookingId", Long.class),
				row -> row.get("seatCount", Long.class).intValue()));
	}
}
