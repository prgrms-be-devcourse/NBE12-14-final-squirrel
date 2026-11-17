package com.squirrel.tickting.domain.payment.facade;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.domain.payment.dto.PaymentRequest;
import com.squirrel.tickting.domain.payment.dto.PaymentResponse;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.service.PaymentService;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.service.ScheduleService;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PaymentExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 준비. 예매(수령 방법·상태), 공연(허용하는 수령 방법·제목), 결제 세 도메인이 얽혀서 서비스끼리 부르지 않고 이 파사드가 엮는다.
 *
 * 같은 예매에 대한 결제 요청은 예매 단위 분산락(booking:{id})으로 한 번에 하나씩만 처리한다.
 * 락을 먼저 잡고 그 안에서 트랜잭션을 열어 커밋한 뒤에 푼다. (doc/part4-booking-area.md 4-7)
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentFacade {

	/** 토스 주문명은 최대 100자라서, 뒤에 붙는 " 99매"를 감안해 공연 제목을 이만큼만 쓴다. */
	private static final int MAX_TITLE_LENGTH = 80;

	private final DistributedLockExecutor lockExecutor;
	private final TransactionTemplate transactionTemplate;
	private final PaymentProperties properties;
	private final BookingService bookingService;
	private final ScheduleService scheduleService;
	private final PaymentService paymentService;

	/**
	 * 결제를 준비한다. 수령 방법과 배송 정보를 예매에 확정하고 결제 준비(READY) 건을 만들어 토스 결제창에 필요한 값을 돌려준다.
	 * 이 단계에서는 좌석 점유 시간을 늘리지 않는다. 결제창을 열어 두기만 하는 사용자가 좌석을 오래 잡는 것을 막으려고,
	 * 연장은 실제 승인을 시작할 때 한 번만 한다.
	 */
	public PaymentResponse.Prepared prepare(Long userId, Long bookingId, PaymentRequest.Prepare request) {
		return lockExecutor.execute(List.of("booking:" + bookingId), properties.lockWait(),
			() -> transactionTemplate.execute(status -> prepareInTransaction(userId, bookingId, request)));
	}

	private PaymentResponse.Prepared prepareInTransaction(Long userId, Long bookingId, PaymentRequest.Prepare request) {
		Booking booking = bookingService.getPayable(userId, bookingId, LocalDateTime.now());
		Performance performance = scheduleService.getPayablePerformance(booking.getPerformanceId());
		validateReceive(performance.getReceiveType(), request);

		bookingService.applyReceiveInfo(booking, request.receiveType(), request.recipientName(),
			request.recipientPhone(), request.recipientAddress());
		Payment payment = paymentService.prepare(booking.getId(), booking.getBookingNo(), booking.getTotalAmount());

		return PaymentResponse.Prepared.from(payment,
			orderName(performance.getTitle(), bookingService.countSeats(booking.getId())), booking.getExpiresAt());
	}

	/** 공연이 받는 수령 방법인지, 배송이면 받는 사람 정보가 모두 있는지 확인한다. */
	private static void validateReceive(ReceiveType allowed, PaymentRequest.Prepare request) {
		boolean delivery = request.receiveType() == ReceiveMethod.DELIVERY;
		boolean permitted = allowed == ReceiveType.BOTH
			|| (delivery ? allowed == ReceiveType.DELIVERY : allowed == ReceiveType.ONSITE);
		if (!permitted) {
			throw new BusinessException(PaymentExceptionCode.RECEIVE_TYPE_NOT_ALLOWED);
		}
		if (delivery && (isBlank(request.recipientName()) || isBlank(request.recipientPhone())
			|| isBlank(request.recipientAddress()))) {
			throw new BusinessException(PaymentExceptionCode.RECIPIENT_REQUIRED);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/** 예: "가상 콘서트 2매" */
	private static String orderName(String title, long seatCount) {
		String shortTitle = title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
		return shortTitle + " " + seatCount + "매";
	}
}
