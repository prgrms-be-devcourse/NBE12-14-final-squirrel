package com.squirrel.tickting.domain.payment.infra.repository;

import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.type.PaymentStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/** 예매의 결제 시도 횟수. 실패한 시도도 센다. 주문 ID 의 일련번호를 정하는 데 쓴다. */
	long countByBookingId(Long bookingId);

	/** 예매에서 아직 승인하지 않은 결제 준비 건. 결제 준비는 락 안에서 불러서 많아야 한 건이다. */
	Optional<Payment> findFirstByBookingIdAndStatusOrderByIdDesc(Long bookingId, PaymentStatus status);
}
