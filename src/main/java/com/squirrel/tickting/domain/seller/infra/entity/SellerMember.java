package com.squirrel.tickting.domain.seller.infra.entity;

import com.squirrel.tickting.domain.seller.type.SellerMemberStatus;
import com.squirrel.tickting.domain.seller.type.SellerRole;
import com.squirrel.tickting.global.entity.SoftDeletableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 판매자 계정. 사업체 1개에 대표(OWNER) 1명과 담당자(STAFF) 여러 명이 속한다. */
@Entity
@Table(name = "seller_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SellerMember extends SoftDeletableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "business_id", nullable = false)
	private SellerBusiness business;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String password;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(nullable = false, length = 20)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SellerRole role;

	/** 본인인증 CI 의 해시. 같은 사업체 안에서 중복될 수 없다. */
	@Column(nullable = false, length = 64)
	private String ciHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SellerMemberStatus status;

	public void deactivate() {
		this.status = SellerMemberStatus.INACTIVE;
	}
}
