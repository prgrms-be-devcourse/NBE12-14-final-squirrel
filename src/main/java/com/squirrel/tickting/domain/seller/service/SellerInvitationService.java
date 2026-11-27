package com.squirrel.tickting.domain.seller.service;

import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.infra.entity.SellerInvitation;
import com.squirrel.tickting.domain.seller.infra.repository.SellerBusinessRepository;
import com.squirrel.tickting.domain.seller.infra.repository.SellerInvitationRepository;
import com.squirrel.tickting.domain.seller.infra.repository.SellerMemberRepository;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.SellerExceptionCode;
import com.squirrel.tickting.global.util.EmailUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
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

	private final SecureRandom random = new SecureRandom();

	/**
	 * 만든 초대와 메일에 넣을 값. 토큰 원문은 응답에 넣으면 안 되고 메일에만 들어가서, 응답 DTO 와 나눠 서비스 안에 둔다.
	 *
	 * @param token 메일 링크에 들어가는 토큰 원문. 어디에도 저장하지 않는다
	 */
	public record Issued(Long invitationId, String email, String companyName, String token, LocalDateTime expiresAt) {
	}

	/**
	 * 초대를 만든다. 이미 판매자 계정이 있는 이메일(비활성 담당자, 다른 회사 계정 포함)이면 EMAIL_DUPLICATED.
	 * 가입 단계까지 가서 실패하면 담당자만 헤매게 되므로 초대할 때 막는다.
	 * 같은 회사가 같은 이메일로 보낸 쓰지 않은 초대는 무효로 만들어서, 가장 최근 링크만 살아 있다.
	 */
	@Transactional
	public Issued invite(Long businessId, Long inviterId, String rawEmail, LocalDateTime now, Duration linkTtl) {
		String email = EmailUtil.normalize(rawEmail);
		if (sellerMemberRepository.existsByEmailAndDeletedAtIsNull(email)) {
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
