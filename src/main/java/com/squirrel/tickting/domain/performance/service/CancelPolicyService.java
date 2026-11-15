package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
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

	/**
	 * 서비스 기본 규정이나 내 사업체 규정 하나. 공연 등록처럼 요청으로 받은 규정 번호를 확인할 때 쓴다.
	 * 남의 규정은 없는 규정과 같은 404로 답한다. (번호가 존재하는지 알려주지 않으려고)
	 */
	@Transactional(readOnly = true)
	public CancelPolicy getVisible(Long cancelPolicyId, Long businessId) {
		return cancelPolicyRepository.findVisible(cancelPolicyId, businessId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.CANCEL_POLICY_NOT_FOUND));
	}
}
