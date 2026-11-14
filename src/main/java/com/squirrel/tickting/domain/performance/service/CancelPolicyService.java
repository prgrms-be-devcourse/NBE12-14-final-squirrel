package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CancelPolicyService {

	private final CancelPolicyRepository cancelPolicyRepository;
	private final CancelPolicyRuleRepository cancelPolicyRuleRepository;

	/** 서비스 기본 규정과 내 사업체 규정. 다른 사업체의 규정은 나오지 않는다. */
	@Transactional(readOnly = true)
	public Page<CancelPolicy> searchVisible(Long businessId, Pageable pageable) {
		return cancelPolicyRepository.searchVisible(businessId, pageable);
	}
}
