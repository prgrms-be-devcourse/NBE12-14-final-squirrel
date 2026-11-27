package com.squirrel.tickting.domain.seller.infra.entity;

import com.squirrel.tickting.global.entity.CreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 담당자 초대. 대표가 담당자 이메일로 보낸 초대 링크 한 건이다. (doc/part1-user-area.md 1-3)
 * 링크를 쓰거나(used_at), 만료되거나, 같은 회사가 같은 이메일로 다시 초대하면(expires_at 을 지금으로 당김) 더 쓸 수 없다.
 * 값은 조건부 UPDATE 로만 바꾼다. (SellerInvitationRepository)
 */
@Entity
@Table(name = "seller_invitation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SellerInvitation extends CreatedTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 초대한 회사. 가입하면 담당자 계정이 이 회사에 속하고, 링크 확인 화면에 회사 이름을 보여 준다. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "business_id", nullable = false)
	private SellerBusiness business;

	/** 초대한 대표의 계정 번호 (seller_member) */
	@Column(nullable = false)
	private Long invitedBy;

	/** 초대받은 이메일. 소문자로 맞춘 값이고, 가입하는 계정의 로그인 이메일이 된다. */
	@Column(nullable = false)
	private String email;

	/** 링크 토큰의 SHA-256 해시. 원문은 메일에만 있어서 DB 가 읽혀도 링크를 만들 수 없다. (uq_seller_invitation_token) */
	@Column(nullable = false, length = 100)
	private String token;

	@Column(nullable = false)
	private LocalDateTime expiresAt;

	/** 링크로 가입한 시각. 값이 있으면 다시 쓸 수 없다. */
	@Column
	private LocalDateTime usedAt;
}
