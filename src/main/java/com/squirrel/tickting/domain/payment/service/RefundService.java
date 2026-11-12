package com.squirrel.tickting.domain.payment.service;

import com.squirrel.tickting.domain.payment.infra.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RefundService {

	private final RefundRepository refundRepository;
}
