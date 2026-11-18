package com.squirrel.tickting.domain.payment.infra.repository;

import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.type.PaymentStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/** 예매의 결제 시도 횟수. 실패한 시도도 센다. 주문 ID 의 일련번호를 정하는 데 쓴다. */
	long countByBookingId(Long bookingId);

	/** 예매에서 아직 승인하지 않은 결제 준비 건. 결제 준비는 락 안에서 불러서 많아야 한 건이다. */
	Optional<Payment> findFirstByBookingIdAndStatusOrderByIdDesc(Long bookingId, PaymentStatus status);

	/** 토스에 보낸 주문 ID 로 찾는다. 주문 ID 는 UNIQUE 라 한 건이거나 없다. */
	Optional<Payment> findByOrderId(String orderId);

	/** 예매에서 결제 준비 상태가 아닌 건의 수. 승인을 이미 시도한 적이 있는지 보는 데 쓴다. */
	long countByBookingIdAndStatusNot(Long bookingId, PaymentStatus status);

	/** 예매에서 가장 최근의, 주어진 상태 중 하나인 결제. 내 예매 상세에 승인된 결제를 보여 주는 데 쓴다. */
	Optional<Payment> findFirstByBookingIdAndStatusInOrderByIdDesc(Long bookingId, Collection<PaymentStatus> statuses);
}
