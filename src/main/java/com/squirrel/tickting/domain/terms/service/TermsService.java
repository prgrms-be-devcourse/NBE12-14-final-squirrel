package com.squirrel.tickting.domain.terms.service;

import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.infra.entity.UserTermsAgreement;
import com.squirrel.tickting.domain.terms.infra.repository.TermsRepository;
import com.squirrel.tickting.domain.terms.infra.repository.UserTermsAgreementRepository;
import com.squirrel.tickting.domain.terms.type.TermsType;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.TermsExceptionCode;
import java.time.LocalDateTime;
import java.util.Comparator;
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
	 * 종류별 최신 약관. type 을 주면 그 종류만 돌려주고, 그 종류의 약관이 없으면 빈 목록이다.
	 * "최신"의 기준을 가입 검증(validateAgreement)과 같은 쿼리 하나로 두려고, 종류 하나만 볼 때도 따로 조회하지 않고 걸러 낸다.
	 * (약관은 종류마다 한 건이라 많아야 몇 건이다)
	 * 저장 순서와 상관없이 화면에 같은 순서로 보이도록 종류 순(서비스 이용 → 개인정보 → 탈퇴)으로 정렬한다.
	 */
	@Transactional(readOnly = true)
	public List<Terms> getLatest(TermsType type) {
		return termsRepository.findLatestOfEachType().stream()
			.filter(terms -> type == null || terms.getType() == type)
			.sorted(Comparator.comparing(Terms::getType))
			.toList();
	}

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
