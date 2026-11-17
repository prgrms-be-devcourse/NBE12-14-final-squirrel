package com.squirrel.tickting.domain.payment.service;

import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.repository.PaymentRepository;
import com.squirrel.tickting.domain.payment.type.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

	private static final String ORDER_ID_PREFIX = "BK";

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

	/** 예: BK20261125KCZB03R-01. 토스 주문 ID 규칙(6~64자, 영문·숫자·-·_)에 맞는다. */
	private static String orderId(String bookingNo, long sequence) {
		return ORDER_ID_PREFIX + bookingNo + "-" + String.format("%02d", sequence);
	}
}
