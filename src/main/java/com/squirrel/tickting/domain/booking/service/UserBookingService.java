package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.dto.UserBookingResponse;
import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.repository.UserBookingRepository;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 예매자(User) 화면의 예매 조회. 예매 도메인을 예매 생성·취소(C)와 판매자 조회(A)가 함께 쓰므로,
 * 같은 파일을 동시에 고치지 않게 역할 이름을 붙여 나눈다.
 * 공연 제목·회차 일시·좌석·결제는 예매의 읽기 전용 연관에서 꺼내므로, 지연 로딩이 되도록 응답을 이 트랜잭션 안에서 만든다.
 */
@Service
@RequiredArgsConstructor
public class UserBookingService {

	/** 최근 예매가 먼저. 같은 시각이면 번호가 큰 쪽이 먼저라 페이지를 넘겨도 순서가 바뀌지 않는다. */
	private static final Sort LATEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

	/**
	 * 내 예매 목록에 보이는 상태. 만료(EXPIRED)는 돈이 나가지 않은 무효 예매라 내역에서 뺀다.
	 * 결제 대기(PENDING)는 남긴다. 화면이 목록에서 결제 화면으로 이어 가게 해서, 7분 안에는 결제를 마저 할 수 있어야 하기 때문이다.
	 * 화면에서 거르면 한 페이지에 보이는 개수와 전체 개수가 어긋나므로 조회할 때 거른다.
	 */
	private static final Set<BookingStatus> VISIBLE_STATUSES = EnumSet.of(
		BookingStatus.PENDING, BookingStatus.PAID, BookingStatus.PARTIAL_CANCELED, BookingStatus.CANCELED);

	private final UserBookingRepository userBookingRepository;
	private final ObjectMapper objectMapper;
	private final CancelPolicyCalculator cancelPolicyCalculator;

	/**
	 * 한 예매자의 예매 목록. 상태를 주지 않으면(null) 내 예매에 보이는 상태가 모두 나온다.
	 * 보이지 않는 상태(EXPIRED)를 고르면 400 이다. pageable 은 페이지 번호와 크기만 쓰고 정렬은 최신 예매순으로 고정한다.
	 *
	 * @deprecated 역할별 예매 조회를 BookingQueryService 로 모았다. 새 코드는 BookingQueryService.search 를 쓴다. 이미 쓰는 곳이 있어서 지우지 않고 남겨 둔다.
	 */
	@Deprecated(since = "2026-11-25")
	@Transactional(readOnly = true)
	public Page<UserBookingResponse.Summary> getMyBookings(Long userId, BookingStatus status, Pageable pageable) {
		if (status != null && !VISIBLE_STATUSES.contains(status)) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT);
		}
		Collection<BookingStatus> statuses = status == null ? VISIBLE_STATUSES : EnumSet.of(status);
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
		return userBookingRepository.findMine(userId, statuses, sorted).map(UserBookingResponse.Summary::from);
	}

	/**
	 * 내 예매 하나. 목록과 달리 상태와 상관없이 찾는다. (결제 화면이 결제 대기 예매를 읽고, 7분이 지나면 만료됐다고 보여 줘야 한다)
	 * 남의 예매도 없는 예매와 같은 404 로 답해서 번호가 있는지 알려주지 않는다.
	 *
	 * @deprecated 역할별 예매 조회를 BookingQueryService 로 모았다. 새 코드는 BookingQueryService.getFullDetail 을 쓴다. 이미 쓰는 곳이 있어서 지우지 않고 남겨 둔다.
	 */
	@Deprecated(since = "2026-11-25")
	@Transactional(readOnly = true)
	public UserBookingResponse.Detail getMyBooking(Long userId, Long bookingId) {
		Booking booking = userBookingRepository.findByIdAndUserId(bookingId, userId)
			.orElseThrow(() -> new BusinessException(BookingExceptionCode.BOOKING_NOT_FOUND));
		CancelPolicyCalculator.Quote quote = cancelPolicyCalculator.quote(booking, LocalDateTime.now());
		return UserBookingResponse.Detail.from(booking, readCancelPolicy(booking), quote);
	}

	/** 탈퇴를 막아야 하는 진행 중인 예매(결제 대기, 공연이 시작하지 않았거나 취소돼 환불을 기다리는 결제 완료)가 있는지. */
	@Transactional(readOnly = true)
	public boolean hasOngoingBooking(Long userId, LocalDateTime now) {
		return userBookingRepository.existsOngoing(userId, now);
	}

	/**
	 * 예매할 때 복사해 둔 취소 규정 구간. 판매자가 나중에 규정을 바꿔도 이 예매는 예매 당시 규정을 따른다.
	 * 공연에서 먼 구간부터(daysFrom 큰 순) 돌려준다.
	 */
	private List<UserBookingResponse.CancelRule> readCancelPolicy(Booking booking) {
		UserBookingResponse.CancelRule[] rules =
			objectMapper.readValue(booking.getCancelPolicySnapshot(), UserBookingResponse.CancelRule[].class);
		return Arrays.stream(rules)
			.sorted(Comparator.comparing(UserBookingResponse.CancelRule::daysFrom).reversed())
			.toList();
	}
}
