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

/**
 * 공연장 좌석 원본. 판매 오픈 전날 회차별 판매 좌석(schedule_seat)으로 복사된다.
 * 공연이 한 번이라도 잡힌 공연장의 좌석은 수정·삭제하지 않는다. (판매 좌석이 이 행을 가리킨다)
 */
@Entity
@Table(name = "venue_seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class VenueSeat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "venue_id", nullable = false)
	private Venue venue;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "zone_id", nullable = false)
	private VenueZone zone;

	/** 9열, 스탠딩처럼 숫자가 아닌 값도 있어서 문자열이다. */
	@Column(nullable = false, length = 30)
	private String rowLabel;

	/** 6, BALCONY-1 처럼 문자도 가능하다. */
	@Column(nullable = false, length = 30)
	private String seatNumber;

	/** 문자열은 정렬하면 "10"이 "2"보다 앞에 와서, 표시 순서를 따로 저장한다. */
	@Column(nullable = false)
	private Integer sortOrder;
}
