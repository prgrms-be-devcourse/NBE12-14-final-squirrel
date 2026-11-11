package com.squirrel.tickting.domain.seller.service;

import com.squirrel.tickting.domain.auth.identity.CiHasher;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.infra.repository.SellerBusinessRepository;
import com.squirrel.tickting.domain.seller.infra.repository.SellerMemberRepository;
import com.squirrel.tickting.domain.seller.type.SellerBusinessStatus;
import com.squirrel.tickting.domain.seller.type.SellerMemberStatus;
import com.squirrel.tickting.domain.seller.type.SellerRole;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.exception.code.SellerExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.security.PasswordVerifier;
import com.squirrel.tickting.global.util.EmailUtil;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판매자 사업체와 계정만 다룬다. 토큰 발급처럼 다른 도메인이 얽히는 흐름은 AuthFacade 가 이어 준다. */
@Service
@RequiredArgsConstructor
public class SellerMemberService {

	private final SellerBusinessRepository sellerBusinessRepository;
	private final SellerMemberRepository sellerMemberRepository;
	private final PasswordEncoder passwordEncoder;
	private final PasswordVerifier passwordVerifier;

	/**
	 * 판매자 가입 신청. 사업체(승인 대기)와 대표자 계정을 한 트랜잭션으로 만든다.
	 * 둘 중 하나만 저장되는 일이 없어야 하므로, 어느 쪽에서 실패해도 전부 되돌린다.
	 * 동시 요청이 아래 중복 확인을 함께 통과하면 DB 의 UNIQUE 인덱스가 막고,
	 * GlobalExceptionHandler 가 같은 중복 에러(이메일·사업자번호)로 바꿔 준다.
	 */
	@Transactional
	public SellerBusiness signup(SellerSignupCommand command) {
		if (!command.identity().name().equals(command.representativeName().trim())) {
			throw new BusinessException(SellerExceptionCode.REPRESENTATIVE_NAME_MISMATCH);
		}
		String email = EmailUtil.normalize(command.email());

		if (sellerBusinessRepository.existsByBusinessNumber(command.businessNumber())) {
			throw new BusinessException(SellerExceptionCode.BUSINESS_NUMBER_DUPLICATED);
		}
		if (sellerMemberRepository.existsByEmailAndDeletedAtIsNull(email)) {
			throw new BusinessException(SellerExceptionCode.EMAIL_DUPLICATED);
		}

		SellerBusiness business = sellerBusinessRepository.save(SellerBusiness.builder()
			.companyName(command.companyName().trim())
			.representativeName(command.representativeName().trim())
			.businessNumber(command.businessNumber())
			.contactPhone(command.contactPhone())
			.contactEmail(EmailUtil.normalize(command.contactEmail()))
			.termsAgreedAt(LocalDateTime.now())
			.status(SellerBusinessStatus.PENDING)
			.build());

		sellerMemberRepository.save(SellerMember.builder()
			.business(business)
			.email(email)
			.password(passwordEncoder.encode(command.password()))
			.name(command.identity().name())
			.phone(command.identity().phone())
			.role(SellerRole.OWNER)
			.ciHash(CiHasher.hash(command.identity().ci()))
			.status(SellerMemberStatus.ACTIVE)
			.build());
		return business;
	}

	/**
	 * 이메일·비밀번호 로그인.
	 * 비밀번호가 맞은 뒤에만 계정·사업체 상태를 알려 준다. (틀린 사람에게 사업체 상태를 노출하지 않는다)
	 */
	@Transactional(readOnly = true)
	public CustomUserDetails login(String rawEmail, String rawPassword) {
		SellerMember member = sellerMemberRepository.findForLoginByEmail(EmailUtil.normalize(rawEmail))
			.orElse(null);

		if (!passwordVerifier.matches(rawPassword, member == null ? null : member.getPassword())) {
			throw new BusinessException(AuthExceptionCode.INVALID_CREDENTIALS);
		}
		requireLoginable(member);
		return toAccount(member);
	}

	/** 토큰 재발급용. 토큰이 아니라 DB 를 기준으로 계정과 사업체가 아직 쓸 수 있는 상태인지 확인한다. */
	@Transactional(readOnly = true)
	public CustomUserDetails getLoginAccount(Long memberId) {
		SellerMember member = sellerMemberRepository.findForLoginById(memberId)
			.orElseThrow(() -> new BusinessException(AuthExceptionCode.TOKEN_INVALID));
		requireLoginable(member);
		return toAccount(member);
	}

	private void requireLoginable(SellerMember member) {
		if (member.getStatus() != SellerMemberStatus.ACTIVE) {
			throw new BusinessException(AuthExceptionCode.ACCOUNT_DISABLED);
		}
		switch (member.getBusiness().getStatus()) {
			case PENDING -> throw new BusinessException(SellerExceptionCode.BUSINESS_PENDING);
			case REJECTED -> throw new BusinessException(SellerExceptionCode.BUSINESS_REJECTED);
			case SUSPENDED -> throw new BusinessException(SellerExceptionCode.BUSINESS_SUSPENDED);
			case APPROVED -> {
			}
		}
	}

	private CustomUserDetails toAccount(SellerMember member) {
		return new CustomUserDetails(member.getId(), AccountType.SELLER, member.getRole().name(),
			member.getBusiness().getId());
	}
}
