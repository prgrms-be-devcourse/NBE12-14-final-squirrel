package com.squirrel.tickting.domain.payment.facade;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.domain.payment.dto.PaymentRequest;
import com.squirrel.tickting.domain.payment.dto.PaymentResponse;
import com.squirrel.tickting.domain.payment.gateway.GatewayPayment;
import com.squirrel.tickting.domain.payment.gateway.GatewayRejectedException;
import com.squirrel.tickting.domain.payment.gateway.GatewayUnavailableException;
import com.squirrel.tickting.domain.payment.gateway.PaymentGateway;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.service.PaymentService;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.domain.performance.service.ScheduleService;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import com.squirrel.tickting.global.exception.code.PaymentExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 준비·승인. 예매(수령 방법·상태), 공연(허용하는 수령 방법·제목·좌석), 결제 세 도메인이 얽혀서 서비스끼리 부르지 않고 이 파사드가 엮는다.
 *
 * 같은 예매에 대한 결제 요청은 예매 단위 분산락(booking:{id})으로 한 번에 하나씩만 처리한다.
 * 락을 먼저 잡고 그 안에서 트랜잭션을 열어 커밋한 뒤에 푼다. (doc/part4-booking-area.md 4-7)
 */
@Slf4j
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
	private final ScheduleSeatService scheduleSeatService;
	private final PaymentService paymentService;
	private final PaymentGateway paymentGateway;

	/**
	 * 결제를 준비한다. 수령 방법과 배송 정보를 예매에 확정하고 결제 준비(READY) 건을 만들어 토스 결제창에 필요한 값을 돌려준다.
	 * 이 단계에서는 좌석 점유 시간을 늘리지 않는다. 결제창을 열어 두기만 하는 사용자가 좌석을 오래 잡는 것을 막으려고,
	 * 연장은 실제 승인을 시작할 때 한 번만 한다.
	 */
	public PaymentResponse.Prepared prepare(Long userId, Long bookingId, PaymentRequest.Prepare request) {
		return lockExecutor.execute(List.of(bookingLock(bookingId)), properties.lockWait(),
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

	/**
	 * 결제를 승인한다. 세 단계로 나뉘고, 토스를 부르는 두 번째 단계만 DB 트랜잭션과 락 밖이다.
	 *
	 *   1. 시작 (락 + 트랜잭션): 내 결제인지, 준비 상태인지, 금액이 서버가 계산한 값과 같은지, 점유 시간이 남았는지 확인하고
	 *      결제를 승인 중(PROCESSING)으로 바꾼다. 처음 시도라면 점유 시간(예매 결제 제한 시각·좌석 점유 시각)을 늘린다.
	 *      금액이나 시간이 어긋나면 토스를 부르지 않고 거절한다. 토스는 승인 API 를 부르기 전에는 돈을 빼지 않아서 돈이 나가지 않는다.
	 *   2. 승인 (트랜잭션 밖): 토스에 승인을 요청한다. 응답이 느려도 DB 연결과 락을 붙잡지 않는다.
	 *   3. 반영 (트랜잭션): 성공하면 결제 DONE, 예매 PAID, 좌석 SOLD 를 한 트랜잭션으로 바꾼다. 거절당하면 결제를 FAILED 로 기록한다.
	 *
	 * 승인 여부를 알 수 없을 때(통신 오류, 시간 초과)와 승인은 됐는데 우리 쪽 반영이 실패했을 때는 결제를 승인 중으로 남기고
	 * 502 로 답한다. 실패로 단정하면 돈은 나갔는데 실패로 기록되기 때문이다. 이런 결제는 5분 보정 작업이 토스에 조회해서 확정한다.
	 *
	 * 3단계는 락을 다시 잡지 않는다. 승인 중 상태가 같은 결제의 다른 승인 요청을 막고 있고, 락 대기에 실패하면
	 * 돈이 나간 뒤에 사용자가 409 를 받게 되기 때문이다.
	 */
	public PaymentResponse.Confirmed confirm(Long userId, PaymentRequest.Confirm request) {
		Long bookingId = findOwnedBookingId(userId, request.orderId());

		Approval approval = lockExecutor.execute(List.of(bookingLock(bookingId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> startApproval(userId, request)));

		GatewayPayment approved = requestApproval(request, approval);

		try {
			return transactionTemplate.execute(status -> completeApproval(approval, approved));
		} catch (RuntimeException e) {
			// 승인은 됐는데 반영에 실패했다. 트랜잭션이 되돌려져 결제는 승인 중으로 남고, 보정 작업이 이어서 확정한다.
			log.error("토스 승인은 끝났지만 결제 반영에 실패했다. 보정 작업이 확정해야 한다. 결제 번호: {}, 주문 ID: {}",
				approval.paymentId(), approval.orderId(), e);
			throw new BusinessException(PaymentExceptionCode.PAYMENT_RESULT_UNKNOWN);
		}
	}

	/** 승인 시작에 쓰려고 읽은 값. 토스에 보내는 금액은 요청이 아니라 서버가 저장한 금액이다. */
	private record Approval(Long paymentId, Long bookingId, String orderId, int amount) {
	}

	/** 내 결제의 예매 번호를 찾는다. 없는 주문 ID 와 남의 결제는 같은 404 로 답해서 주문 ID 가 존재하는지 알려주지 않는다. */
	private Long findOwnedBookingId(Long userId, String orderId) {
		Payment payment = paymentService.getByOrderId(orderId);
		bookingService.findMine(userId, payment.getBookingId())
			.orElseThrow(() -> new BusinessException(PaymentExceptionCode.PAYMENT_NOT_FOUND));
		return payment.getBookingId();
	}

	private Approval startApproval(Long userId, PaymentRequest.Confirm request) {
		LocalDateTime now = LocalDateTime.now();
		Payment payment = paymentService.getByOrderId(request.orderId());
		Booking booking = bookingService.findMine(userId, payment.getBookingId())
			.orElseThrow(() -> new BusinessException(PaymentExceptionCode.PAYMENT_NOT_FOUND));

		// 이번 결제를 승인 중으로 바꾸기 전에 센다. 바꾼 뒤에 세면 이번 결제가 "시도한 적 있음"으로 잡혀 점유를 늘리지 못한다.
		boolean attempted = paymentService.hasAttemptedApproval(booking.getId());
		// 아래 검사에서 거절하면 예외로 트랜잭션이 되돌려져 결제는 준비(READY) 상태로 돌아간다.
		paymentService.startProcessing(payment, request.paymentKey(), now);
		if (!payment.getAmount().equals(request.amount())) {
			throw new BusinessException(PaymentExceptionCode.AMOUNT_MISMATCH);
		}
		if (booking.getStatus() != BookingStatus.PENDING || !booking.getExpiresAt().isAfter(now)) {
			throw new BusinessException(BookingExceptionCode.BOOKING_NOT_PAYABLE);
		}

		if (!attempted) {
			extendHold(booking, now);
		}
		return new Approval(payment.getId(), booking.getId(), payment.getOrderId(), payment.getAmount());
	}

	/**
	 * 토스를 부르는 동안 7분이 지나 스케줄러가 좌석을 풀어 버리면 돈은 나갔는데 좌석이 다른 사람에게 팔릴 수 있어서 점유를 늘린다.
	 * 남은 시간이 늘릴 시간보다 짧을 때만 늘린다. 예매당 한 번만 늘리는 것은 호출한 쪽이 처음 시도일 때만 부르는 것으로 지킨다.
	 * (승인을 시작했다 실패하고 다시 시작하기를 반복해 좌석을 계속 잡는 것을 막으려는 것이다)
	 * 예매의 결제 제한 시각과 좌석의 점유 시각은 같은 트랜잭션에서 같은 값으로 늘린다.
	 */
	private void extendHold(Booking booking, LocalDateTime now) {
		LocalDateTime extendedUntil = now.plus(properties.confirmExtension());
		if (!booking.getExpiresAt().isBefore(extendedUntil)) {
			return;
		}
		bookingService.extendExpiry(booking, extendedUntil);
		scheduleSeatService.extendHold(bookingService.findActiveScheduleSeatIds(booking.getId()), extendedUntil, now);
	}

	private GatewayPayment requestApproval(PaymentRequest.Confirm request, Approval approval) {
		try {
			return paymentGateway.confirm(request.paymentKey(), approval.orderId(), approval.amount());
		} catch (GatewayRejectedException e) {
			transactionTemplate.executeWithoutResult(status -> paymentService.fail(approval.paymentId(), e.getMessage()));
			throw new BusinessException(PaymentExceptionCode.PAYMENT_REJECTED, e.getMessage());
		} catch (GatewayUnavailableException e) {
			log.warn("토스 승인 결과를 알 수 없어 결제를 승인 중으로 남긴다. 결제 번호: {}, 주문 ID: {}",
				approval.paymentId(), approval.orderId());
			throw new BusinessException(PaymentExceptionCode.PAYMENT_RESULT_UNKNOWN);
		}
	}

	private PaymentResponse.Confirmed completeApproval(Approval approval, GatewayPayment approved) {
		LocalDateTime now = LocalDateTime.now();
		Payment payment = paymentService.complete(approval.paymentId(), approved.paymentKey(), approved.method(),
			approved.approvedAt());
		Booking booking = bookingService.markPaid(approval.bookingId(), now);
		scheduleSeatService.markSold(bookingService.findActiveScheduleSeatIds(approval.bookingId()), now);
		return PaymentResponse.Confirmed.from(booking, payment);
	}

	private static String bookingLock(Long bookingId) {
		return "booking:" + bookingId;
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
