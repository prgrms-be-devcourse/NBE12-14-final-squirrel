package com.squirrel.tickting.domain.payment.infra.repository;

import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.type.PaymentStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/**
	 * 보정 작업이 토스에 조회할 승인 중(PROCESSING) 결제. 승인을 시작한 지 cutoff 이전인 것만이다. 지금 응답을 기다리는 승인을
	 * 건드리지 않으려고 준비한 시각이 아니라 승인을 시작한 시각으로 잰다. 오래된 것부터. (부분 인덱스 idx_payment_reconcile_processing)
	 */
	@Query(value = """
		SELECT id FROM payment
		WHERE status = 'PROCESSING' AND processing_started_at < :cutoff
		ORDER BY processing_started_at
		LIMIT :limit
		""", nativeQuery = true)
	List<Long> findProcessingDueIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

	/** 보정 작업이 닫을 준비(READY) 결제. 준비한 지 cutoff 이전인 것만이다. 오래된 것부터. (부분 인덱스 idx_payment_reconcile_ready) */
	@Query(value = """
		SELECT id FROM payment
		WHERE status = 'READY' AND created_at < :cutoff
		ORDER BY created_at
		LIMIT :limit
		""", nativeQuery = true)
	List<Long> findReadyDueIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

	/** 같은 예매의 다른 결제 중 주어진 상태인 것이 있는가. 한 결제가 닫혀도 예매를 살리는 다른 결제가 있는지 보는 데 쓴다. */
	boolean existsByBookingIdAndIdNotAndStatusIn(Long bookingId, Long id, Collection<PaymentStatus> statuses);

	/** 예매의 결제 시도 횟수. 실패한 시도도 센다. 주문 ID 의 일련번호를 정하는 데 쓴다. */
	long countByBookingId(Long bookingId);

	/** 예매에서 아직 승인하지 않은 결제 준비 건. 결제 준비는 락 안에서 불러서 많아야 한 건이다. */
	Optional<Payment> findFirstByBookingIdAndStatusOrderByIdDesc(Long bookingId, PaymentStatus status);

	/** 토스에 보낸 주문 ID 로 찾는다. 주문 ID 는 UNIQUE 라 한 건이거나 없다. */
	Optional<Payment> findByOrderId(String orderId);

	/** 예매에서 주어진 상태인 가장 최근 결제. 환불할 결제(승인된 결제)를 찾는 데 쓴다. */
	Optional<Payment> findFirstByBookingIdAndStatusInOrderByIdDesc(Long bookingId, Collection<PaymentStatus> statuses);

	/** 예매에서 결제 준비 상태가 아닌 건의 수. 승인을 이미 시도한 적이 있는지 보는 데 쓴다. */
	long countByBookingIdAndStatusNot(Long bookingId, PaymentStatus status);
}
