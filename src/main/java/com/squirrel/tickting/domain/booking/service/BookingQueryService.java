package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.dto.BookingSearchRequest;
import com.squirrel.tickting.domain.booking.dto.UserBookingResponse;
import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.repository.BookingQueryRepository;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 예매자·판매자·관리자가 함께 쓰는 예매 목록·상세 조회. 역할마다 API 주소와 응답 모양은 다르고, 찾는 방법만 여기서 한 번 쓴다.
 * 누구의 예매까지 보이는지는 컨트롤러가 토큰으로 만든 BookingScope 로 정한다. 남의 예매는 없는 예매와 같은 404 다.
 * 응답은 예매의 읽기 전용 연관(공연·회차·좌석·결제)에서 꺼내므로, 호출한 쪽이 넘긴 변환 함수를 이 트랜잭션 안에서 실행한다.
 */
@Service
@RequiredArgsConstructor
public class BookingQueryService {

	/** 예매자 목록에 보이는 상태. 만료(EXPIRED)는 돈이 나가지 않은 무효 예매라 내역에서 빼고, 골라도 400 이다. */
	private static final Set<BookingStatus> USER_VISIBLE = EnumSet.of(BookingStatus.PENDING, BookingStatus.PAID,
		BookingStatus.PARTIAL_CANCELED, BookingStatus.CANCELED);

	/** 판매자가 상태를 고르지 않았을 때: 남은 표가 있는 예매. 현장 확인·배송·응대에 남은 표가 없는 예매는 필요 없다. */
	private static final Set<BookingStatus> SELLER_DEFAULT = EnumSet.of(BookingStatus.PAID,
		BookingStatus.PARTIAL_CANCELED);

	/** 날짜를 비웠을 때의 구간. null 을 넘기지 않으려고 서비스가 생기기 전과 아주 먼 뒤로 채운다. */
	private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
	private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

	private final BookingQueryRepository bookingQueryRepository;
	private final ObjectMapper objectMapper;
	private final CancelPolicyCalculator cancelPolicyCalculator;

	/**
	 * 예매 목록. 정렬은 최신 예매순으로 고정하고, pageable 은 페이지 번호와 크기만 쓴다.
	 * 시작 날짜가 끝 날짜보다 늦거나 예매자가 볼 수 없는 상태를 고르면 400 이다.
	 */
	@Transactional(readOnly = true)
	public <T> Page<T> search(BookingScope scope, BookingSearchRequest request, Pageable pageable,
		Function<Booking, T> toItem) {
		Collection<BookingStatus> statuses = statuses(scope, request.status());
		LocalDate fromDate = request.from() == null ? MIN_DATE : request.from();
		LocalDate toDate = request.to() == null ? MAX_DATE : request.to();
		if (fromDate.isAfter(toDate)) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT);
		}
		LocalDateTime from = fromDate.atStartOfDay();
		LocalDateTime to = toDate.plusDays(1).atStartOfDay();
		String title = titlePattern(request.title());
		Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

		Page<Booking> bookings = switch (scope) {
			case BookingScope.OfUser user ->
				bookingQueryRepository.searchOfUser(user.userId(), statuses, from, to, title, page);
			case BookingScope.OfBusiness business ->
				bookingQueryRepository.searchOfBusiness(business.businessId(), statuses, from, to, title, page);
			case BookingScope.All all -> bookingQueryRepository.searchAll(statuses, from, to, title, page);
		};
		return bookings.map(toItem);
	}

	/** 예매 하나. 상태와 상관없이 찾는다. (결제 화면이 결제 대기 예매를 읽는다) */
	@Transactional(readOnly = true)
	public <T> T getDetail(BookingScope scope, Long bookingId, Function<Booking, T> toDetail) {
		return toDetail.apply(find(scope, bookingId));
	}

	/**
	 * 결제·환불·취소 규정까지 담은 예매 하나. 예매자 상세와 관리자 상세가 쓴다.
	 * 취소 가능 여부·지금 수수료율은 예매 취소와 같은 계산기에서 받아, 화면에 보인 수수료와 실제 환불이 어긋나지 않는다.
	 */
	@Transactional(readOnly = true)
	public <T> T getFullDetail(BookingScope scope, Long bookingId,
		BiFunction<Booking, UserBookingResponse.Detail, T> toDetail) {
		Booking booking = find(scope, bookingId);
		CancelPolicyCalculator.Quote quote = cancelPolicyCalculator.quote(booking, LocalDateTime.now());
		UserBookingResponse.Detail detail = UserBookingResponse.Detail.from(booking, readCancelPolicy(booking),
			quote.cancelable(), quote.feeRate());
		return toDetail.apply(booking, detail);
	}

	private Booking find(BookingScope scope, Long bookingId) {
		return (switch (scope) {
			case BookingScope.OfUser user -> bookingQueryRepository.findByIdAndUserId(bookingId, user.userId());
			case BookingScope.OfBusiness business ->
				bookingQueryRepository.findByIdAndBusinessId(bookingId, business.businessId());
			case BookingScope.All all -> bookingQueryRepository.findById(bookingId);
		}).orElseThrow(() -> new BusinessException(BookingExceptionCode.BOOKING_NOT_FOUND));
	}

	/** 고른 상태가 없으면 역할별 기본 상태. 예매자는 보이는 상태 밖을 고르면 400 이다. */
	private static Collection<BookingStatus> statuses(BookingScope scope, List<BookingStatus> requested) {
		boolean empty = requested == null || requested.isEmpty();
		return switch (scope) {
			case BookingScope.OfUser user -> {
				if (empty) {
					yield USER_VISIBLE;
				}
				if (!USER_VISIBLE.containsAll(requested)) {
					throw new BusinessException(CommonExceptionCode.INVALID_INPUT);
				}
				yield EnumSet.copyOf(requested);
			}
			case BookingScope.OfBusiness business -> empty ? SELLER_DEFAULT : EnumSet.copyOf(requested);
			case BookingScope.All all -> empty ? EnumSet.allOf(BookingStatus.class) : EnumSet.copyOf(requested);
		};
	}

	/** 제목 부분 일치용 LIKE 패턴. 사용자가 넣은 %·_ 가 와일드카드로 쓰이지 않게 '!' 로 이스케이프한다. 비우면 빈 문자열. */
	private static String titlePattern(String title) {
		if (title == null || title.isBlank()) {
			return "";
		}
		String escaped = title.strip().toLowerCase(Locale.ROOT)
			.replace("!", "!!")
			.replace("%", "!%")
			.replace("_", "!_");
		return "%" + escaped + "%";
	}

	/** 예매할 때 복사해 둔 취소 규정 구간. 공연에서 먼 구간부터(daysFrom 큰 순) 돌려준다. */
	private List<UserBookingResponse.CancelRule> readCancelPolicy(Booking booking) {
		UserBookingResponse.CancelRule[] rules =
			objectMapper.readValue(booking.getCancelPolicySnapshot(), UserBookingResponse.CancelRule[].class);
		return Arrays.stream(rules)
			.sorted(Comparator.comparing(UserBookingResponse.CancelRule::daysFrom).reversed())
			.toList();
	}
}
