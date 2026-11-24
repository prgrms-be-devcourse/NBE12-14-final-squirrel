package com.squirrel.tickting.domain.payment.service;

import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.repository.PaymentRepository;
import com.squirrel.tickting.domain.payment.type.PaymentStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PaymentExceptionCode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

	private static final String ORDER_ID_PREFIX = "BK";
	private static final List<PaymentStatus> REFUNDABLE_STATUSES =
		List.of(PaymentStatus.DONE, PaymentStatus.PARTIAL_CANCELED);

	private final PaymentRepository paymentRepository;

	/**
	 * 결제 준비(READY) 건을 돌려준다. 이미 승인을 기다리는 준비 건이 있으면 새로 만들지 않고 그것을 그대로 돌려준다.
	 * 결제창을 닫았다 다시 열거나 화면을 새로고침해서 준비를 다시 요청해도 주문 ID 가 늘어나지 않게 하려는 것이다.
	 * 이전 시도가 실패(FAILED)했다면 일련번호를 하나 올려 새 주문 ID 로 만든다. 토스에는 한 번 쓴 주문 ID 로 다시 결제할 수 없다고 가정한다.
	 *
	 * 같은 예매의 준비 요청이 동시에 들어오면 둘 다 "없다"고 보고 만들 수 있어서, 호출하는 쪽이 예매 단위 분산락 안에서 불러야 한다.
	 * 그래도 뚫리면 주문 ID 의 UNIQUE 인덱스(uq_payment_order_id)가 같은 번호가 두 번 만들어지는 것을 막는다.
	 *
	 * @param amount 서버가 계산한 결제 금액. 클라이언트가 보낸 값은 승인 때 이 값과 비교만 한다
	 */
	@Transactional
	public Payment prepare(Long bookingId, String bookingNo, int amount) {
		return paymentRepository.findFirstByBookingIdAndStatusOrderByIdDesc(bookingId, PaymentStatus.READY)
			.orElseGet(() -> paymentRepository.save(Payment.builder()
				.bookingId(bookingId)
				.orderId(orderId(bookingNo, paymentRepository.countByBookingId(bookingId) + 1))
				.amount(amount)
				.status(PaymentStatus.READY)
				.build()));
	}

	/** 토스에 보낸 주문 ID 로 결제를 찾는다. 없으면 404. 호출한 쪽의 트랜잭션에 참여한다. */
	@Transactional
	public Payment getByOrderId(String orderId) {
		return paymentRepository.findByOrderId(orderId)
			.orElseThrow(() -> new BusinessException(PaymentExceptionCode.PAYMENT_NOT_FOUND));
	}

	/**
	 * 이 예매에서 승인을 이미 시도한 결제가 있는지. 결제 준비(READY)가 아닌 건이 하나라도 있으면 시도한 적이 있는 것이다.
	 * 점유 시간을 늘리는 것은 예매당 한 번만이라서, 이 값이 true 면 늘리지 않는다.
	 */
	@Transactional(readOnly = true)
	public boolean hasAttemptedApproval(Long bookingId) {
		return paymentRepository.countByBookingIdAndStatusNot(bookingId, PaymentStatus.READY) > 0;
	}

	/**
	 * 승인을 시작한다. 결제 준비(READY)가 아니면 이미 다른 요청이 처리 중이거나 끝난 것이라 409 다.
	 * 호출한 쪽의 트랜잭션과 예매 단위 락 안에서 불러야 같은 결제의 승인 요청이 두 번 시작되지 않는다.
	 */
	@Transactional
	public void startProcessing(Payment payment) {
		if (payment.getStatus() != PaymentStatus.READY) {
			throw new BusinessException(PaymentExceptionCode.PAYMENT_ALREADY_PROCESSED);
		}
		payment.startProcessing();
	}

	/** 토스 승인 결과를 반영한다. 승인을 시작(PROCESSING)한 결제만 완료할 수 있다. */
	@Transactional
	public Payment complete(Long paymentId, String paymentKey, String method, LocalDateTime approvedAt) {
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		payment.complete(paymentKey, method, approvedAt);
		return payment;
	}

	/** 토스가 승인을 거절했을 때 실패로 기록한다. 좌석 점유와 예매는 건드리지 않고 만료 시각까지 그대로 둔다. */
	@Transactional
	public void fail(Long paymentId, String reason) {
		paymentRepository.findById(paymentId).orElseThrow().fail(reason);
	}

	/**
	 * 예매의 환불할 결제. 승인된 뒤 아직 전액 환불되지 않은 결제(DONE·PARTIAL_CANCELED)다.
	 * 결제 완료된 예매라면 반드시 있어야 해서, 없으면 예매와 결제가 어긋난 것이라 IllegalStateException 이다.
	 */
	@Transactional(readOnly = true)
	public Payment getRefundable(Long bookingId) {
		return paymentRepository.findFirstByBookingIdAndStatusInOrderByIdDesc(bookingId, REFUNDABLE_STATUSES)
			.orElseThrow(() -> new IllegalStateException("환불할 결제가 없다. 예매 번호: " + bookingId));
	}

	/** 환불을 반영한다. 남은 좌석이 없으면 전액 환불, 있으면 일부 환불 상태가 된다. 호출한 쪽의 트랜잭션에 참여한다. */
	@Transactional
	public void applyCancel(Payment payment, boolean fullyCanceled) {
		payment.applyCancel(fullyCanceled);
	}

	/** 예: BK20261125KCZB03R-01. 토스 주문 ID 규칙(6~64자, 영문·숫자·-·_)에 맞는다. */
	private static String orderId(String bookingNo, long sequence) {
		return ORDER_ID_PREFIX + bookingNo + "-" + String.format("%02d", sequence);
	}
}
