package com.squirrel.tickting.domain.payment.infra.repository;

import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
