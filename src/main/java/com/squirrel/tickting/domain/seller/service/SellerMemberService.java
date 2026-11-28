package com.squirrel.tickting.domain.seller.service;

import com.squirrel.tickting.domain.seller.dto.SellerMemberResponse;
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
import java.util.List;
import java.util.Optional;
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
	 * 가입 전 이메일 인증번호를 보내도 되는 이메일인지 확인한다. 그 이메일의 사용 중(활성) 판매자 계정이 있으면 EMAIL_DUPLICATED.
	 * 가입 API 의 중복 확인과 같은 기준이다. 가입할 수 없는 이메일로 번호를 보내 봐야 소용없어서 미리 막는다.
	 * 다른 회사에서 비활성화된 계정만 있으면 새 회사로 가입할 수 있다. (활성 계정은 이메일당 하나, uq_seller_email_active)
	 */
	@Transactional(readOnly = true)
	public void requireEmailNotTaken(String normalizedEmail) {
		if (sellerMemberRepository.existsByEmailAndStatusAndDeletedAtIsNull(normalizedEmail, SellerMemberStatus.ACTIVE)) {
			throw new BusinessException(SellerExceptionCode.EMAIL_DUPLICATED);
		}
	}

	/**
	 * 판매자 가입 신청. 사업체(승인 대기)와 대표자 계정을 한 트랜잭션으로 만든다.
	 * 둘 중 하나만 저장되는 일이 없어야 하므로, 어느 쪽에서 실패해도 전부 되돌린다.
	 * 동시 요청이 아래 중복 확인을 함께 통과하면 DB 의 UNIQUE 인덱스가 막고,
	 * GlobalExceptionHandler 가 같은 중복 에러(이메일·사업자번호)로 바꿔 준다.
	 */
	@Transactional
	public SellerBusiness signup(SellerSignupCommand command) {
		// DB 조회가 필요 없는 검사라 중복 확인보다 먼저 한다
		if (!command.password().equals(command.passwordConfirm())) {
			throw new BusinessException(AuthExceptionCode.PASSWORD_CONFIRM_MISMATCH);
		}
		String email = EmailUtil.normalize(command.email());

		if (sellerBusinessRepository.existsByBusinessNumber(command.businessNumber())) {
			throw new BusinessException(SellerExceptionCode.BUSINESS_NUMBER_DUPLICATED);
		}
		if (sellerMemberRepository.existsByEmailAndStatusAndDeletedAtIsNull(email, SellerMemberStatus.ACTIVE)) {
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
			.name(command.name())
			.phone(command.phone())
			.role(SellerRole.OWNER)
			.status(SellerMemberStatus.ACTIVE)
			.build());
		return business;
	}

	/**
	 * 이메일·비밀번호 로그인.
	 * 비밀번호가 맞은 뒤에만 계정·사업체 상태를 알려 준다. (틀린 사람에게 사업체 상태를 노출하지 않는다)
	 * 트랜잭션을 걸지 않는다. 걸면 BCrypt 비교(수십 ms, 로그인이 몰리면 수 초) 동안 DB 연결을 쥐고 있어서 풀이 바닥나고 다른 API 까지 멈춘다.
	 * 사업체는 조회할 때 JOIN FETCH 로 함께 읽어서 트랜잭션이 끝난 뒤 꺼내도 지연 로딩 오류가 나지 않는다. (조회를 바꾸면 이 점을 지킨다)
	 * 같은 이메일의 계정이 회사마다 있으면 활성 계정으로 로그인한다. 활성 계정이 없으면 가장 최근 계정으로 확인해서 "비활성화된 계정"으로 답한다.
	 */
	public CustomUserDetails login(String rawEmail, String rawPassword) {
		SellerMember member = representative(EmailUtil.normalize(rawEmail)).orElse(null);

		if (!passwordVerifier.matches(rawPassword, member == null ? null : member.getPassword())) {
			throw new BusinessException(AuthExceptionCode.INVALID_CREDENTIALS);
		}
		requireLoginable(member);
		return toAccount(member);
	}

	/** 대표가 보는 내 회사의 모든 계정(대표 포함, 비활성 포함). (AUTH-26) */
	@Transactional(readOnly = true)
	public List<SellerMemberResponse.Member> getMembers(Long businessId) {
		return sellerMemberRepository.findAllOfBusiness(businessId).stream()
			.map(SellerMemberResponse.Member::from)
			.toList();
	}

	/**
	 * 담당자를 비활성화한다. (AUTH-27) 확인 순서: 없거나 다른 회사 계정(404) → 대표 계정(400) → 이미 비활성(409).
	 * 계정은 지우지 않아서 그 사람이 한 작업 이력이 남는다. 이미 로그인해 있던 세션은 SellerAccountStatusInterceptor 가 다음 요청에서 막는다.
	 * 확인한 뒤 같은 계정을 동시에 비활성화하면 조건부 UPDATE 가 0행이라 이미 비활성(409)으로 답한다.
	 */
	@Transactional
	public SellerMemberResponse.Member deactivate(Long businessId, Long memberId) {
		SellerMember member = getMine(businessId, memberId);
		if (member.getRole() == SellerRole.OWNER) {
			throw new BusinessException(SellerExceptionCode.OWNER_NOT_DEACTIVATABLE);
		}
		if (member.getStatus() == SellerMemberStatus.INACTIVE || sellerMemberRepository.changeStaffStatus(memberId,
			SellerMemberStatus.ACTIVE, SellerMemberStatus.INACTIVE, LocalDateTime.now()) == 0) {
			throw new BusinessException(SellerExceptionCode.MEMBER_ALREADY_INACTIVE);
		}
		return SellerMemberResponse.Member.from(getMine(businessId, memberId));
	}

	/**
	 * 비활성 담당자를 다시 사용 중으로 바꾼다. (AUTH-28) 비밀번호는 예전 그대로다.
	 * 확인 순서: 없거나 다른 회사 계정(404) → 이미 활성(409 SELLER012) → 그 이메일의 활성 계정이 다른 회사에 있음(409 SELLER013).
	 * 활성 계정은 이메일당 하나라서, 다른 회사에서 아직 비활성화되지 않은 사람은 복구할 수 없다. 확인한 뒤 다른 회사에서 먼저 활성화되면
	 * uq_seller_email_active 가 막고 SELLER000 이 된다.
	 */
	@Transactional
	public SellerMemberResponse.Member reactivate(Long businessId, Long memberId) {
		SellerMember member = getMine(businessId, memberId);
		if (member.getStatus() == SellerMemberStatus.ACTIVE) {
			throw new BusinessException(SellerExceptionCode.MEMBER_ALREADY_ACTIVE);
		}
		if (sellerMemberRepository.existsByEmailAndStatusAndDeletedAtIsNull(member.getEmail(), SellerMemberStatus.ACTIVE)) {
			throw new BusinessException(SellerExceptionCode.EMAIL_ACTIVE_IN_OTHER_BUSINESS);
		}
		if (sellerMemberRepository.changeStaffStatus(memberId, SellerMemberStatus.INACTIVE, SellerMemberStatus.ACTIVE,
			LocalDateTime.now()) == 0) {
			throw new BusinessException(SellerExceptionCode.MEMBER_ALREADY_ACTIVE);
		}
		return SellerMemberResponse.Member.from(getMine(businessId, memberId));
	}

	/**
	 * 판매자 계정이 비활성이거나 삭제됐는가. 비활성화된 계정의 남은 세션을 막는 데 쓴다. 판매자 요청마다 불려서 조회 하나로 끝낸다.
	 * 세션은 로그인으로만 만들어져서 계정 행이 없는 세션은 생기지 않는다. 그래서 막아야 할 상태(비활성·삭제)인지만 묻는다.
	 */
	@Transactional(readOnly = true)
	public boolean isDisabled(Long memberId) {
		return sellerMemberRepository.isDisabled(memberId);
	}

	/**
	 * 내 회사의 판매자 계정 하나. 없거나 다른 회사 계정이면 MEMBER_NOT_FOUND.
	 * 문의 작성처럼 다른 도메인이 작성자 이름·이메일을 복사할 때도 파사드가 이걸 부른다.
	 */
	@Transactional(readOnly = true)
	public SellerMember getMine(Long businessId, Long memberId) {
		return sellerMemberRepository.findByIdAndBusinessIdAndDeletedAtIsNull(memberId, businessId)
			.orElseThrow(() -> new BusinessException(SellerExceptionCode.MEMBER_NOT_FOUND));
	}

	/** 이메일로 로그인·비밀번호 재설정에 쓸 계정 하나. 활성 계정이 먼저이고, 없으면 가장 최근 계정이다. */
	private Optional<SellerMember> representative(String normalizedEmail) {
		return sellerMemberRepository.findAccountsByEmail(normalizedEmail).stream().findFirst();
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

	/**
	 * 비밀번호 재설정 메일을 받을 수 있는 판매자 계정(대표·담당자)을 읽는다. 삭제됐거나 없으면 PASSWORD_RESET_EMAIL_NOT_FOUND.
	 * 계정·사업체 상태와 무관하게 허용한다. 승인 대기 중인 대표가 비밀번호를 잊어도 방법이 있어야 하고, 로그인 차단은 로그인이 그대로 맡는다.
	 * 같은 이메일의 계정이 회사마다 있으면 로그인과 같은 계정(활성 계정, 없으면 가장 최근 계정)의 비밀번호를 바꾼다.
	 */
	@Transactional(readOnly = true)
	public SellerMember getPasswordResetTarget(String rawEmail) {
		return representative(EmailUtil.normalize(rawEmail))
			.orElseThrow(() -> new BusinessException(AuthExceptionCode.PASSWORD_RESET_EMAIL_NOT_FOUND,
				"판매자 계정으로 가입된 이메일이 아닙니다."));
	}

	/**
	 * 비밀번호를 새로 정하고, 알림 메일을 보낼 로그인 이메일을 돌려준다. 그 사이 삭제됐으면 PASSWORD_RESET_TOKEN_INVALID.
	 * 트랜잭션을 열지 않는다. 해시 계산(BCrypt)이 오래 걸려서, 열면 그동안 DB 연결을 쥐고 있게 된다. UPDATE 하나만 짧게 트랜잭션이다.
	 */
	public String resetPassword(Long memberId, String rawPassword, LocalDateTime now) {
		SellerMember member = sellerMemberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new BusinessException(AuthExceptionCode.PASSWORD_RESET_TOKEN_INVALID));
		if (sellerMemberRepository.updatePassword(memberId, passwordEncoder.encode(rawPassword), now) == 0) {
			throw new BusinessException(AuthExceptionCode.PASSWORD_RESET_TOKEN_INVALID);
		}
		return member.getEmail();
	}
}
