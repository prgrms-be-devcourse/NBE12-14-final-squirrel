package com.squirrel.tickting.domain.admin.service;

import com.squirrel.tickting.domain.admin.infra.entity.AdminMember;
import com.squirrel.tickting.domain.admin.infra.repository.AdminMemberRepository;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.security.PasswordVerifier;
import com.squirrel.tickting.global.util.EmailUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 계정만 다룬다. 가입이 없어서 로그인과 토큰 재발급용 조회만 있다.
 * 토큰 발급처럼 다른 도메인이 얽히는 흐름은 AuthFacade 가 이어 준다.
 */
@Service
@RequiredArgsConstructor
public class AdminMemberService {

	private final AdminMemberRepository adminMemberRepository;
	private final PasswordVerifier passwordVerifier;

	/** 이메일·비밀번호 로그인. 어느 쪽이 틀렸는지는 알려 주지 않는다. */
	@Transactional(readOnly = true)
	public CustomUserDetails login(String rawEmail, String rawPassword) {
		AdminMember admin = adminMemberRepository.findByEmailAndDeletedAtIsNull(EmailUtil.normalize(rawEmail))
			.orElse(null);

		if (!passwordVerifier.matches(rawPassword, admin == null ? null : admin.getPassword())) {
			throw new BusinessException(AuthExceptionCode.INVALID_CREDENTIALS);
		}
		requireActive(admin);
		return toAccount(admin);
	}

	/** 토큰 재발급용. 토큰이 아니라 DB 를 기준으로 계정이 아직 쓸 수 있는 상태인지 확인한다. */
	@Transactional(readOnly = true)
	public CustomUserDetails getLoginAccount(Long adminId) {
		AdminMember admin = adminMemberRepository.findByIdAndDeletedAtIsNull(adminId)
			.orElseThrow(() -> new BusinessException(AuthExceptionCode.TOKEN_INVALID));
		requireActive(admin);
		return toAccount(admin);
	}

	public CustomUserDetails toAccount(AdminMember admin) {
		return new CustomUserDetails(admin.getId(), AccountType.ADMIN, null, null);
	}

	private void requireActive(AdminMember admin) {
		if (!admin.isActive()) {
			throw new BusinessException(AuthExceptionCode.ACCOUNT_DISABLED);
		}
	}
}
