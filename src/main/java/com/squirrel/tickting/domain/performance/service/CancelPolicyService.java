package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CancelPolicyService {

	private final CancelPolicyRepository cancelPolicyRepository;
	private final CancelPolicyRuleRepository cancelPolicyRuleRepository;
}
