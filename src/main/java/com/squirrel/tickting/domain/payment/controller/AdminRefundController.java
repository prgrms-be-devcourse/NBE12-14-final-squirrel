package com.squirrel.tickting.domain.payment.controller;

import com.squirrel.tickting.domain.payment.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {

	private final RefundService refundService;
}
