package com.squirrel.tickting.domain.user.service;

import com.squirrel.tickting.domain.auth.identity.CiHasher;
import com.squirrel.tickting.domain.auth.identity.VerifiedIdentity;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.infra.repository.UserMemberRepository;
import com.squirrel.tickting.domain.user.type.UserStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.exception.code.UserExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.security.PasswordVerifier;
import com.squirrel.tickting.global.util.EmailUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매자 계정만 다룬다. 약관·토큰처럼 다른 도메인이 얽히는 흐름은 AuthFacade 가 이어 준다.
 */
@Service
@RequiredArgsConstructor
public class UserMemberService {

	private final UserMemberRepository userMemberRepository;
	private final PasswordEncoder passwordEncoder;
	private final PasswordVerifier passwordVerifier;

	/**
	 * 예매자를 만든다. 이메일·본인인증(CI) 중복이면 BusinessException. 호출한 쪽의 트랜잭션에 참여한다.
	 * 동시 요청이 이 확인을 함께 통과하면 DB 의 UNIQUE 인덱스가 막고, GlobalExceptionHandler 가 같은 에러로 바꿔 준다.
	 */
	@Transactional
	public UserMember create(String rawEmail, String rawPassword, String nickname, VerifiedIdentity identity) {
		String email = EmailUtil.normalize(rawEmail);
		String ciHash = CiHasher.hash(identity.ci());

		if (userMemberRepository.existsByEmailAndDeletedAtIsNull(email)) {
			throw new BusinessException(UserExceptionCode.EMAIL_DUPLICATED);
		}
		if (userMemberRepository.existsByCiHashAndDeletedAtIsNull(ciHash)) {
			throw new BusinessException(UserExceptionCode.ALREADY_REGISTERED);
		}

		return userMemberRepository.save(UserMember.builder()
			.email(email)
			.password(passwordEncoder.encode(rawPassword))
			.name(identity.name())
			.nickname(nickname.trim())
			.phone(identity.phone())
			.ciHash(ciHash)
			.birthDate(identity.birthDate())
			.status(UserStatus.ACTIVE)
			.build());
	}

	/** 이메일·비밀번호 로그인. 어느 쪽이 틀렸는지는 알려 주지 않는다. */
	@Transactional(readOnly = true)
	public CustomUserDetails login(String rawEmail, String rawPassword) {
		UserMember user = userMemberRepository.findByEmailAndDeletedAtIsNull(EmailUtil.normalize(rawEmail))
			.orElse(null);

		if (!passwordVerifier.matches(rawPassword, user == null ? null : user.getPassword())) {
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

	/**
	 * 로그인한 예매자 본인의 정보. 탈퇴한 회원이면 USER_NOT_FOUND.
	 * 정지(SUSPENDED)된 회원은 막지 않는다. 자기 정보를 읽기만 하는 것이라 위험하지 않고, 로그인과 토큰 재발급에서 이미 막힌다.
	 */
	@Transactional(readOnly = true)
	public UserMember getMe(Long userId) {
		return userMemberRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(() -> new BusinessException(UserExceptionCode.USER_NOT_FOUND));
	}

	public CustomUserDetails toAccount(UserMember user) {
		return new CustomUserDetails(user.getId(), AccountType.USER, null, null);
	}

	private void requireActive(UserMember user) {
		if (!user.isActive()) {
			throw new BusinessException(AuthExceptionCode.ACCOUNT_DISABLED);
		}
	}
}
