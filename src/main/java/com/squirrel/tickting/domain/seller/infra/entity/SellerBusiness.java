package com.squirrel.tickting.domain.seller.infra.entity;

import com.squirrel.tickting.domain.seller.type.SellerBusinessStatus;
import com.squirrel.tickting.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 판매자 사업체. 공연과 정산의 주인이고, 관리자의 승인을 받아야 소속 계정이 로그인할 수 있다. */
@Entity
@Table(name = "seller_business")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SellerBusiness extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String companyName;

	/** 본인인증으로 확인된 이름과 같아야 한다. */
	@Column(nullable = false, length = 50)
	private String representativeName;

	@Column(nullable = false, length = 20)
	private String businessNumber;

	@Column(nullable = false, length = 20)
	private String contactPhone;

	@Column(nullable = false)
	private String contactEmail;

	@Column(nullable = false)
	private LocalDateTime termsAgreedAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SellerBusinessStatus status;

	@Column(length = 500)
	private String rejectReason;

	/** 승인·반려한 관리자 번호 (admin_member) */
	private Long reviewedBy;

	private LocalDateTime reviewedAt;

	public void approve(Long adminId) {
		this.status = SellerBusinessStatus.APPROVED;
		this.rejectReason = null;
		this.reviewedBy = adminId;
		this.reviewedAt = LocalDateTime.now();
	}

	public void reject(Long adminId, String reason) {
		this.status = SellerBusinessStatus.REJECTED;
		this.rejectReason = reason;
		this.reviewedBy = adminId;
		this.reviewedAt = LocalDateTime.now();
	}

	public void suspend() {
		this.status = SellerBusinessStatus.SUSPENDED;
	}
}
