package com.squirrel.tickting.domain.venue.infra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 공연장 구역. 가격과 등급을 구역 단위로 정하고, 좌석 조회도 구역 요약 → 구역 상세 2단계로 한다. */
@Entity
@Table(name = "venue_zone")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class VenueZone {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "venue_id", nullable = false)
	private Venue venue;

	@Column(nullable = false, length = 50)
	private String name;

	/** 1층, 2층 등. 단층이거나 스탠딩이면 비운다. */
	@Column(length = 20)
	private String floor;

	/** 화면에 보여줄 순서 */
	@Column(nullable = false)
	private Integer sortOrder;
}
