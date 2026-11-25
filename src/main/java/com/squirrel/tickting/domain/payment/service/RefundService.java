package com.squirrel.tickting.domain.payment.service;

import com.squirrel.tickting.domain.payment.facade.RefundRetryProperties;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.infra.repository.RefundRepository;
import com.squirrel.tickting.domain.payment.type.RefundReasonType;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefundService {

	private final RefundRepository refundRepository;
	private final RefundRetryProperties retryProperties;

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
			.lastAttemptedAt(now)
			.build());
	}

	/**
	 * 관리자 환불 목록. exhaustedOnly 면 자동 재시도를 다 쓴 것만 준다. 기준 횟수는 재시도 스케줄러 설정을 그대로 읽어서,
	 * 설정을 바꿔도 "스케줄러가 포기한 환불"과 목록이 어긋나지 않는다. 예매번호를 붙이려고 예매를 함께 읽는다.
	 */
	@Transactional(readOnly = true)
	public Page<Refund> searchForAdmin(RefundStatus status, boolean exhaustedOnly, Pageable pageable) {
		int minRetryCount = exhaustedOnly ? retryProperties.maxCount() : 0;
		return refundRepository.searchForAdmin(status, minRetryCount, pageable);
	}

	@Transactional(readOnly = true)
	public List<Long> findRetryDue(LocalDateTime cutoff, int maxCount, int limit) {
		return refundRepository.findRetryDueIds(cutoff, maxCount, limit);
	}

	/**
	 * 재시도할 환불을 요청 중으로 바꾸고 가져간다. 다른 서버가 먼저 가져갔거나 이미 끝난 환불이면 비어 있다.
	 * 실패로 단정하지 않고 비어 있는 값으로 답하는 이유는, 여러 서버가 같은 목록을 보고 경쟁하는 것이 정상이라서다.
	 */
	@Transactional
	public Optional<Refund> claimForRetry(Long refundId, LocalDateTime now, LocalDateTime cutoff, int maxCount) {
		if (refundRepository.claimForRetry(refundId, now, cutoff, maxCount) == 0) {
			return Optional.empty();
		}
		return refundRepository.findWithPaymentById(refundId);
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
