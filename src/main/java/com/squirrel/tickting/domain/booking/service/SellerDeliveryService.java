package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.dto.BookingRequest;
import com.squirrel.tickting.domain.booking.dto.SellerBookingResponse;
import com.squirrel.tickting.domain.booking.facade.BookingProperties;
import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.repository.BookingQueryRepository;
import com.squirrel.tickting.domain.booking.infra.repository.BookingRepository;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자의 발송 처리(SLR-40~42). 배송 테이블 없이 예매의 택배사·송장번호·발송 일시에 저장한다.
 * 락은 걸지 않는다. 취소와 겹치면 상태 조건부 UPDATE 가 막고, 송장을 동시에 넣으면 나중 값이 남는다.
 * 공연이 내 것인지는 호출하는 파사드가 먼저 확인한다. 단건은 예매를 사업체 번호로 찾아서 파사드 없이 부른다.
 */
@Service
@RequiredArgsConstructor
public class SellerDeliveryService {

	private static final Set<BookingStatus> SHIPPABLE = Set.of(BookingStatus.PAID, BookingStatus.PARTIAL_CANCELED);

	private final BookingRepository bookingRepository;
	private final BookingQueryRepository bookingQueryRepository;
	private final BookingProperties properties;

	/** 배송 대상 목록. scheduleId 가 null 이면 모든 회차다. 다른 공연의 회차 번호면 조건에 걸려 빈 목록이다. */
	@Transactional(readOnly = true)
	public List<SellerBookingResponse.DeliveryTarget> getTargets(Long businessId, Long performanceId, Long scheduleId,
		boolean shipped) {
		return bookingRepository.findDeliveryTargets(performanceId, businessId, scheduleId == null,
				scheduleId == null ? 0 : scheduleId, shipped)
			.stream()
			.map(SellerBookingResponse.DeliveryTarget::from)
			.toList();
	}

	/** 송장 한 건. 남의 예매는 없는 예매와 같은 404 이고, 그 뒤에 배송 예매인지와 상태를 본다. */
	@Transactional
	public SellerBookingResponse.Summary ship(Long businessId, Long bookingId, BookingRequest.Tracking request) {
		if (!isCarrier(request.carrier())) {
			throw invalid("carrier: 지원하지 않는 택배사입니다.");
		}
		Booking booking = find(businessId, bookingId);
		BookingExceptionCode rejection = rejection(booking.getReceiveType(), booking.getStatus());
		if (rejection != null) {
			throw new BusinessException(rejection);
		}
		int updated = bookingRepository.ship(bookingId, businessId, request.carrier(), request.trackingNo(),
			LocalDateTime.now());
		if (updated == 0) {
			throw new BusinessException(BookingExceptionCode.BOOKING_NOT_SHIPPABLE);
		}
		// UPDATE 가 영속성 컨텍스트를 비워서, 다시 읽으면 바뀐 값이다
		return SellerBookingResponse.Summary.from(find(businessId, bookingId));
	}

	/** 일괄 입력에서 요청만 보고 하는 검사. 파일이 잘못된 것이라 전체를 거절한다. DB 를 읽기 전에 부른다. */
	public void validate(BookingRequest.TrackingBulk request) {
		int maxSize = properties.delivery().bulkMaxSize();
		if (request.items().size() > maxSize) {
			throw invalid("items: 한 번에 " + maxSize + "건까지 올릴 수 있습니다.");
		}
		Set<String> bookingNos = new HashSet<>();
		for (int row = 0; row < request.items().size(); row++) {
			BookingRequest.TrackingRow item = request.items().get(row);
			if (!isCarrier(item.carrier())) {
				throw invalid("items[" + row + "].carrier: 지원하지 않는 택배사입니다.");
			}
			if (!bookingNos.add(item.bookingNo())) {
				throw invalid("items[" + row + "].bookingNo: 같은 예매 번호가 두 번 들어 있습니다.");
			}
		}
	}

	/**
	 * 송장 일괄 입력. 조회 한 번으로 행마다 가능 여부를 보고, 가능한 행만 UPDATE 한 번으로 반영한다.
	 * 상태 때문에 안 되는 행은 그 행만 실패로 돌려준다. validate 를 통과한 요청이어야 한다.
	 */
	@Transactional
	public SellerBookingResponse.ShipmentResult shipAll(Long businessId, Long performanceId,
		BookingRequest.TrackingBulk request) {
		List<BookingRequest.TrackingRow> items = request.items();
		List<BookingRepository.ShipmentCandidateRow> candidates = bookingRepository.findShipmentCandidates(
			performanceId, businessId, items.stream().map(BookingRequest.TrackingRow::bookingNo).toArray(String[]::new));

		List<BookingExceptionCode> rejections = new ArrayList<>();
		List<BookingRequest.TrackingRow> accepted = new ArrayList<>();
		for (int row = 0; row < items.size(); row++) {
			BookingRepository.ShipmentCandidateRow candidate = candidates.get(row);
			BookingExceptionCode rejection = candidate.getStatus() == null ? BookingExceptionCode.BOOKING_NOT_FOUND
				: rejection(candidate.getReceiveType(), candidate.getStatus());
			rejections.add(rejection);
			if (rejection == null) {
				accepted.add(items.get(row));
			}
		}

		Set<String> shipped = accepted.isEmpty() ? Set.of() : new HashSet<>(bookingRepository.shipAll(performanceId,
			businessId,
			accepted.stream().map(BookingRequest.TrackingRow::bookingNo).toArray(String[]::new),
			accepted.stream().map(BookingRequest.TrackingRow::carrier).toArray(String[]::new),
			accepted.stream().map(BookingRequest.TrackingRow::trackingNo).toArray(String[]::new),
			LocalDateTime.now()));

		List<SellerBookingResponse.ShipmentFailure> failed = new ArrayList<>();
		for (int row = 0; row < items.size(); row++) {
			String bookingNo = items.get(row).bookingNo();
			if (shipped.contains(bookingNo)) {
				continue;
			}
			// 조회 때는 가능했는데 UPDATE 에서 빠졌으면 그사이 취소된 것이다
			BookingExceptionCode code = rejections.get(row) == null ? BookingExceptionCode.BOOKING_NOT_SHIPPABLE
				: rejections.get(row);
			failed.add(SellerBookingResponse.ShipmentFailure.of(row, bookingNo, code));
		}
		return new SellerBookingResponse.ShipmentResult(items.size(), shipped.size(), failed);
	}

	/** 발송할 수 없는 이유. 보낼 수 있으면 null. UPDATE 의 WHERE 조건과 같아야 한다. */
	private static BookingExceptionCode rejection(ReceiveMethod receiveType, BookingStatus status) {
		if (receiveType != ReceiveMethod.DELIVERY) {
			return BookingExceptionCode.NOT_DELIVERY_BOOKING;
		}
		return SHIPPABLE.contains(status) ? null : BookingExceptionCode.BOOKING_NOT_SHIPPABLE;
	}

	private Booking find(Long businessId, Long bookingId) {
		return bookingQueryRepository.findByIdAndBusinessId(bookingId, businessId)
			.orElseThrow(() -> new BusinessException(BookingExceptionCode.BOOKING_NOT_FOUND));
	}

	private boolean isCarrier(String code) {
		return properties.delivery().carriers().stream().anyMatch(carrier -> carrier.code().equals(code));
	}

	private static BusinessException invalid(String message) {
		return new BusinessException(CommonExceptionCode.INVALID_INPUT, message);
	}
}
