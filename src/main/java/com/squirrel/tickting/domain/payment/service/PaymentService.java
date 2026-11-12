package com.squirrel.tickting.domain.payment.service;

import com.squirrel.tickting.domain.payment.infra.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentService {

	private final PaymentRepository paymentRepository;
}
