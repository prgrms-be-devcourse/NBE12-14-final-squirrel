package com.squirrel.tickting.domain.payment.service;

import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.infra.repository.RefundRepository;
import com.squirrel.tickting.domain.payment.type.RefundReasonType;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundService {

	private final RefundRepository refundRepository;

	/**
	 * 토스에 보낼 환불 한 건을 만든다. 처음부터 요청 중(PROCESSING)으로 만들고, 호출한 쪽이 커밋한 뒤에 토스를 부른다.
	 * 토스를 부르는 도중 서버가 죽어도 "보내다 만 환불"이 DB 에 남아서, 재시도가 같은 멱등키로 이어서 처리할 수 있다.
	 * 멱등키는 여기서 한 번 정하고 바꾸지 않는다. (UNIQUE 인덱스 uq_refund_idempotency_key)
	 */
	@Transactional
	public Refund start(Payment payment, Long bookingId, int amount, RefundReasonType reasonType, LocalDateTime now) {
		return refundRepository.save(Refund.builder()
			.payment(payment)
			.bookingId(bookingId)
			.amount(amount)
			.reasonType(reasonType)
			.idempotencyKey(UUID.randomUUID().toString())
			.status(RefundStatus.PROCESSING)
			.requestedAt(now)
			.build());
	}

	@Transactional
	public Refund complete(Long refundId, LocalDateTime now) {
		Refund refund = refundRepository.findById(refundId).orElseThrow();
		refund.complete(now);
		return refund;
	}

	@Transactional
	public Refund fail(Long refundId, String reason) {
		Refund refund = refundRepository.findById(refundId).orElseThrow();
		refund.fail(reason);
		return refund;
	}
}
