package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.dto.SellerBookingResponse;
import com.squirrel.tickting.domain.booking.infra.repository.BookingRepository;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 화면의 예매 조회(SLR-18). 예매 도메인을 예매 생성·취소(C)와 판매자 조회(A)가 함께 쓰므로,
 * 같은 파일을 동시에 고치지 않게 역할 이름을 붙여 나눈다. 회차가 내 것인지는 호출하는 파사드가 먼저 확인한다.
 */
@Service
@RequiredArgsConstructor
public class SellerBookingService {

	/**
	 * 상태를 고르지 않았을 때 보이는 상태: 남은 표가 있는 예매. 목록의 쓰임(현장 확인·배송·응대)에 남은 표가 없는 예매는 필요 없다.
	 * 전체 취소·결제 대기·만료는 status 로 따로 본다.
	 */
	private static final List<BookingStatus> DEFAULT_STATUSES = List.of(BookingStatus.PAID,
		BookingStatus.PARTIAL_CANCELED);

	private final BookingRepository bookingRepository;

	/**
	 * 회차의 예매 목록. 정렬은 쿼리가 최신 예매순으로 고정한다. 좌석은 예매의 읽기 전용 연관에서 꺼내므로
	 * 지연 로딩이 되도록 응답을 이 트랜잭션 안에서 만든다.
	 */
	@Transactional(readOnly = true)
	public Page<SellerBookingResponse.Item> search(Long scheduleId, BookingStatus status, ReceiveMethod receiveType,
		String bookingNo, Pageable pageable) {
		List<BookingStatus> statuses = status == null ? DEFAULT_STATUSES : List.of(status);
		List<ReceiveMethod> receiveTypes = receiveType == null ? Arrays.asList(ReceiveMethod.values())
			: List.of(receiveType);
		String exactBookingNo = bookingNo == null ? "" : bookingNo.strip();
		return bookingRepository.findBySchedule(scheduleId, statuses, receiveTypes, exactBookingNo, pageable)
			.map(SellerBookingResponse.Item::from);
	}
}
