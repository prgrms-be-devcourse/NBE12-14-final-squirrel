package com.squirrel.tickting.domain.seller.service;

import com.squirrel.tickting.domain.seller.dto.SellerInvitationRequest;
import com.squirrel.tickting.domain.seller.dto.SellerInvitationResponse;
import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.infra.entity.SellerInvitation;
import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.infra.repository.SellerBusinessRepository;
import com.squirrel.tickting.domain.seller.infra.repository.SellerInvitationRepository;
import com.squirrel.tickting.domain.seller.infra.repository.SellerMemberRepository;
import com.squirrel.tickting.domain.seller.type.SellerMemberStatus;
import com.squirrel.tickting.domain.seller.type.SellerRole;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.exception.code.SellerExceptionCode;
import com.squirrel.tickting.global.util.EmailUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 담당자 초대 링크를 만들고 확인하고 쓴다. (doc/part1-user-area.md 1-3, doc/api-spec.md AUTH-23~25) */
@Service
@RequiredArgsConstructor
public class SellerInvitationService {

	private static final int TOKEN_BYTES = 32;

	private final SellerInvitationRepository sellerInvitationRepository;
	private final SellerBusinessRepository sellerBusinessRepository;
	private final SellerMemberRepository sellerMemberRepository;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	/**
	 * 만든 초대와 메일에 넣을 값. 토큰 원문은 응답에 넣으면 안 되고 메일에만 들어가서, 응답 DTO 와 나눠 서비스 안에 둔다.
	 *
	 * @param token 메일 링크에 들어가는 토큰 원문. 어디에도 저장하지 않는다
	 */
	public record Issued(Long invitationId, String email, String companyName, String token, LocalDateTime expiresAt) {
	}

	/**
	 * 초대를 만든다. 그 이메일의 사용 중(활성) 판매자 계정이 있으면(어느 회사든) EMAIL_DUPLICATED.
	 * 활성 계정은 이메일당 하나라서 가입 단계까지 가도 실패하므로, 담당자가 헤매지 않게 초대할 때 막는다.
	 * 비활성 계정만 있으면 초대할 수 있다. 같은 회사의 비활성 계정이면 가입할 때 그 계정을 되살린다.
	 * 같은 회사가 같은 이메일로 보낸 쓰지 않은 초대는 무효로 만들어서, 가장 최근 링크만 살아 있다.
	 */
	@Transactional
	public Issued invite(Long businessId, Long inviterId, String rawEmail, LocalDateTime now, Duration linkTtl) {
		String email = EmailUtil.normalize(rawEmail);
		if (sellerMemberRepository.existsByEmailAndStatusAndDeletedAtIsNull(email, SellerMemberStatus.ACTIVE)) {
			throw new BusinessException(SellerExceptionCode.EMAIL_DUPLICATED);
		}
		SellerBusiness business = sellerBusinessRepository.findById(businessId).orElseThrow();
		sellerInvitationRepository.expirePending(businessId, email, now);

		String token = generateToken();
		SellerInvitation invitation = sellerInvitationRepository.save(SellerInvitation.builder()
			.business(business)
			.invitedBy(inviterId)
			.email(email)
			.token(sha256(token))
			.expiresAt(now.plus(linkTtl))
			.build());
		return new Issued(invitation.getId(), email, business.getCompanyName(), token, invitation.getExpiresAt());
	}

	/** 초대를 무효로 만든다. 메일 발송을 접수하지 못했을 때 되돌리는 용도다. 이미 쓴 초대는 그대로 둔다. */
	@Transactional
	public void expire(Long invitationId, LocalDateTime now) {
		sellerInvitationRepository.expire(invitationId, now);
	}

	/** 링크가 아직 쓸 수 있는지 본다. 토큰을 소모하지 않는다. (메일 보안 프로그램이나 링크 미리보기가 열어도 링크가 죽지 않게) */
	@Transactional(readOnly = true)
	public SellerInvitationResponse.Validity validate(SellerInvitationRequest.Validate request) {
		return sellerInvitationRepository.findUsableByToken(sha256(request.token()), now())
			.map(SellerInvitationResponse.Validity::from)
			.orElseGet(SellerInvitationResponse.Validity::invalid);
	}

	/**
	 * 초대 링크로 담당자 계정을 만든다. 확인 순서: 비밀번호 확인(400 AUTH005) → 링크(400 SELLER008) → 활성 계정 중복(409 SELLER000).
	 * 같은 회사에 그 이메일의 비활성 계정이 있으면 새로 만들지 않고 그 계정을 새 비밀번호·이름·휴대폰으로 되살린다. (같은 회사 안에서는 이메일당 계정 하나)
	 * 링크 사용 기록과 계정 생성·재활성화가 한 트랜잭션이라, 실패하면 링크도 소모되지 않는다.
	 * 같은 링크로 동시에 가입하면 markUsed 의 조건이 한 요청만 통과시킨다. 다른 회사에서 같은 이메일 계정이 동시에 활성화되면
	 * 확인을 함께 통과해도 uq_seller_email_active 가 막고 SELLER000 으로 바뀐다. (UniqueConstraintCodes)
	 */
	@Transactional
	public SellerInvitationResponse.Accepted accept(SellerInvitationRequest.Accept request) {
		// DB 를 보지 않고 끝낼 수 있는 검사라 링크 확인보다 먼저 한다
		if (!request.password().equals(request.passwordConfirm())) {
			throw new BusinessException(AuthExceptionCode.PASSWORD_CONFIRM_MISMATCH);
		}
		LocalDateTime now = now();
		SellerInvitation invitation = sellerInvitationRepository.findUsableByToken(sha256(request.token()), now)
			.orElseThrow(() -> new BusinessException(SellerExceptionCode.INVITATION_INVALID));
		String email = invitation.getEmail();
		Long businessId = invitation.getBusiness().getId();
		if (sellerMemberRepository.existsByEmailAndStatusAndDeletedAtIsNull(email, SellerMemberStatus.ACTIVE)) {
			throw new BusinessException(SellerExceptionCode.EMAIL_DUPLICATED);
		}
		if (sellerInvitationRepository.markUsed(invitation.getId(), now) == 0) {
			throw new BusinessException(SellerExceptionCode.INVITATION_INVALID);
		}
		String password = passwordEncoder.encode(request.password());
		Optional<SellerMember> sameBusiness = sellerMemberRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, email);
		if (sameBusiness.isPresent()) {
			Long memberId = sameBusiness.get().getId();
			// 활성 계정이 없다고 확인했으니 이 계정은 비활성이다. 0행이면 그 사이 대표가 복구해서 이미 활성이 된 것이다
			if (sellerMemberRepository.reactivateWithProfile(memberId, password, request.name(), request.phone(), now) == 0) {
				throw new BusinessException(SellerExceptionCode.EMAIL_DUPLICATED);
			}
			return SellerInvitationResponse.Accepted.from(sellerMemberRepository.findById(memberId).orElseThrow(), true);
		}
		SellerMember member = sellerMemberRepository.save(SellerMember.builder()
			.business(sellerBusinessRepository.getReferenceById(businessId))
			.email(email)
			.password(password)
			.name(request.name())
			.phone(request.phone())
			.role(SellerRole.STAFF)
			.status(SellerMemberStatus.ACTIVE)
			.build());
		return SellerInvitationResponse.Accepted.from(member, false);
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
	}

	private String generateToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
		}
	}
}
