package com.squirrel.tickting.domain.venue.infra.entity;

import com.squirrel.tickting.domain.venue.type.VenueRequestStatus;
import com.squirrel.tickting.global.entity.BaseTimeEntity;
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

/** 공연장 신청. 소통은 사이트 밖(메일, 전화)에서 하고 신청 기록과 처리 상태만 남긴다. */
@Entity
@Table(name = "venue_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class VenueRequest extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 신청한 사업체 번호 (seller_business) */
	@Column(nullable = false)
	private Long businessId;

	/** 신청한 판매자 계정 번호 (seller_member) */
	@Column(nullable = false)
	private Long requestedBy;

	@Column(nullable = false, length = 100)
	private String venueName;

	@Column
	private String address;

	@Column(nullable = false, length = 20)
	private String contactPhone;

	/** 좌석 배치도를 받을 이메일 */
	@Column(nullable = false)
	private String contactEmail;

	@Column(columnDefinition = "text")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private VenueRequestStatus status;

	/** 사이트 밖 소통 내용(메일·전화)을 남긴다. */
	@Column(columnDefinition = "text")
	private String adminMemo;

	/** 등록 불가일 때만 값이 있다. */
	@Column(length = 500)
	private String rejectReason;

	/** 처리한 관리자 번호 (admin_member) */
	@Column
	private Long handledBy;

	/** 등록 완료 후 만들어진 공연장. 완료 전에는 비어 있다. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "venue_id")
	private Venue venue;
}
