package com.squirrel.tickting.domain.terms.service;

import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.infra.entity.UserTermsAgreement;
import com.squirrel.tickting.domain.terms.infra.repository.TermsRepository;
import com.squirrel.tickting.domain.terms.infra.repository.UserTermsAgreementRepository;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.TermsExceptionCode;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TermsService {

	private final TermsRepository termsRepository;
	private final UserTermsAgreementRepository agreementRepository;

	/**
	 * 가입 요청의 약관 동의를 확인하고, 동의한 약관 목록을 돌려준다.
	 *  - 없는 약관 번호면 거절
	 *  - 개정되어 최신이 아닌 약관이면 거절
	 *  - 종류별 최신 약관 중 필수인 것에 모두 동의하지 않았으면 거절
	 */
	@Transactional(readOnly = true)
	public List<Terms> validateAgreement(List<Long> agreedTermIds) {
		Set<Long> agreedIds = agreedTermIds == null ? Set.of() : new LinkedHashSet<>(agreedTermIds);

		List<Terms> agreed = termsRepository.findAllById(agreedIds);
		if (agreed.size() != agreedIds.size()) {
			throw new BusinessException(TermsExceptionCode.TERMS_NOT_FOUND);
		}

		List<Terms> latest = termsRepository.findLatestOfEachType();
		Set<Long> latestIds = latest.stream().map(Terms::getId).collect(Collectors.toSet());
		if (!latestIds.containsAll(agreedIds)) {
			throw new BusinessException(TermsExceptionCode.TERMS_OUTDATED);
		}

		boolean allRequiredAgreed = latest.stream()
			.filter(Terms::isRequired)
			.allMatch(terms -> agreedIds.contains(terms.getId()));
		if (!allRequiredAgreed) {
			throw new BusinessException(TermsExceptionCode.REQUIRED_TERMS_NOT_AGREED);
		}
		return agreed;
	}

	/** 동의 기록을 남긴다. 회원을 저장한 트랜잭션 안에서 호출한다. */
	@Transactional
	public void recordAgreements(Long userId, List<Terms> agreedTerms) {
		LocalDateTime now = LocalDateTime.now();
		List<UserTermsAgreement> agreements = agreedTerms.stream()
			.map(terms -> UserTermsAgreement.builder()
				.userId(userId)
				.termsId(terms.getId())
				.agreedAt(now)
				.build())
			.toList();
		agreementRepository.saveAll(agreements);
	}
}
