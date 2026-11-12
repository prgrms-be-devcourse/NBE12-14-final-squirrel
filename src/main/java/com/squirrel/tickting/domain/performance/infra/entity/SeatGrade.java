package com.squirrel.tickting.domain.performance.infra.entity;

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

/** 좌석 등급. 같은 공연장이라도 공연마다 가격 체계가 달라서 공연 단위로 만든다. 등급마다 가격은 1개다. */
@Entity
@Table(name = "seat_grade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SeatGrade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "performance_id", nullable = false)
	private Performance performance;

	/** VIP석, R석, S석 등 */
	@Column(nullable = false, length = 50)
	private String name;

	/** 원. 판매 좌석을 만든 뒤에는 수정할 수 없다. (이미 복사된 좌석 가격과 어긋나기 때문) */
	@Column(nullable = false)
	private Integer price;

	/** 좌석도에 칠할 색 (예: #E8503A) */
	@Column(length = 20)
	private String color;
}
