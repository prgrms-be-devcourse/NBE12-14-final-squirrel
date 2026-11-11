package com.squirrel.tickting.domain.user.service;

import com.squirrel.tickting.domain.auth.identity.CiHasher;
import com.squirrel.tickting.domain.auth.identity.VerifiedIdentity;
import com.squirrel.tickting.domain.terms.infra.entity.Terms;
import com.squirrel.tickting.domain.terms.service.TermsService;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.infra.repository.UserMemberRepository;
import com.squirrel.tickting.domain.user.type.UserStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.exception.code.UserExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.util.EmailUtil;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class UserMemberService {

	private final UserMemberRepository userMemberRepository;
	private final TermsService termsService;
	private final PasswordEncoder passwordEncoder;
	private final TransactionTemplate transactionTemplate;

	/** 없는 이메일로 로그인할 때도 비밀번호 비교를 한 번 하게 해서, 응답 시간으로 가입 여부를 알아내지 못하게 한다. */
	private final String dummyPasswordHash;

	public UserMemberService(UserMemberRepository userMemberRepository, TermsService termsService,
		PasswordEncoder passwordEncoder, TransactionTemplate transactionTemplate) {
		this.userMemberRepository = userMemberRepository;
		this.termsService = termsService;
		this.passwordEncoder = passwordEncoder;
		this.transactionTemplate = transactionTemplate;
		this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
	}

	/**
	 * 예매자 가입. 회원 저장과 약관 동의 기록이 한 트랜잭션이다.
	 *
	 * 중복은 미리 확인하지만, 동시에 같은 값으로 요청하면 둘 다 확인을 통과할 수 있다.
	 * 그 경우 DB 의 부분 UNIQUE 인덱스가 막고, 여기서 중복 에러로 바꿔 준다.
	 * 트랜잭션 안에서는 DB 가 이미 실패 상태라 조회할 수 없어서, 트랜잭션 밖에서 원인을 확인한다.
	 */
	public CustomUserDetails signup(String rawEmail, String rawPassword, String nickname,
		VerifiedIdentity identity, List<Long> agreedTermIds) {
		String email = EmailUtil.normalize(rawEmail);
		String ciHash = CiHasher.hash(identity.ci());

		try {
			UserMember saved = transactionTemplate.execute(status ->
				create(email, rawPassword, nickname, identity, ciHash, agreedTermIds));
			return toAccount(saved);
		} catch (DataIntegrityViolationException e) {
			throw translateDuplicate(email, ciHash, e);
		}
	}

	private UserMember create(String email, String rawPassword, String nickname,
		VerifiedIdentity identity, String ciHash, List<Long> agreedTermIds) {
		List<Terms> agreedTerms = termsService.validateAgreement(agreedTermIds);

		if (userMemberRepository.existsByEmailAndDeletedAtIsNull(email)) {
			throw new BusinessException(UserExceptionCode.EMAIL_DUPLICATED);
		}
		if (userMemberRepository.existsByCiHashAndDeletedAtIsNull(ciHash)) {
			throw new BusinessException(UserExceptionCode.ALREADY_REGISTERED);
		}

		UserMember user = userMemberRepository.save(UserMember.builder()
			.email(email)
			.password(passwordEncoder.encode(rawPassword))
			.name(identity.name())
			.nickname(nickname.trim())
			.phone(identity.phone())
			.ciHash(ciHash)
			.birthDate(identity.birthDate())
			.status(UserStatus.ACTIVE)
			.build());
		termsService.recordAgreements(user.getId(), agreedTerms);
		return user;
	}

	private RuntimeException translateDuplicate(String email, String ciHash, DataIntegrityViolationException e) {
		if (userMemberRepository.existsByEmailAndDeletedAtIsNull(email)) {
			return new BusinessException(UserExceptionCode.EMAIL_DUPLICATED);
		}
		if (userMemberRepository.existsByCiHashAndDeletedAtIsNull(ciHash)) {
			return new BusinessException(UserExceptionCode.ALREADY_REGISTERED);
		}
		return e;
	}

	/** 이메일·비밀번호 로그인. 어느 쪽이 틀렸는지는 알려 주지 않는다. */
	@Transactional(readOnly = true)
	public CustomUserDetails login(String rawEmail, String rawPassword) {
		UserMember user = userMemberRepository.findByEmailAndDeletedAtIsNull(EmailUtil.normalize(rawEmail))
			.orElse(null);

		boolean passwordMatches = passwordEncoder.matches(rawPassword,
			user == null ? dummyPasswordHash : user.getPassword());
		if (user == null || !passwordMatches) {
			throw new BusinessException(AuthExceptionCode.INVALID_CREDENTIALS);
		}
		requireActive(user);
		return toAccount(user);
	}

	/** 토큰 재발급용. 토큰이 아니라 DB 를 기준으로 계정이 아직 쓸 수 있는 상태인지 확인한다. */
	@Transactional(readOnly = true)
	public CustomUserDetails getLoginAccount(Long userId) {
		UserMember user = userMemberRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(() -> new BusinessException(AuthExceptionCode.TOKEN_INVALID));
		requireActive(user);
		return toAccount(user);
	}

	private void requireActive(UserMember user) {
		if (!user.isActive()) {
			throw new BusinessException(AuthExceptionCode.ACCOUNT_DISABLED);
		}
	}

	private CustomUserDetails toAccount(UserMember user) {
		return new CustomUserDetails(user.getId(), AccountType.USER, null, null);
	}
}
