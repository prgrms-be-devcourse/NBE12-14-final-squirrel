package com.squirrel.tickting.domain.performance.infra.entity;

import com.squirrel.tickting.domain.performance.type.SeatGradeName;
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

	/** 등급 코드. 한 공연에 같은 등급은 하나다. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private SeatGradeName name;

	/** 원. 판매 좌석을 만든 뒤에는 수정할 수 없다. (이미 복사된 좌석 가격과 어긋나기 때문) */
	@Column(nullable = false)
	private Integer price;

	/** 등급과 가격을 한꺼번에 바꾼다(전체 교체). 좌석 생성 후에는 부르는 쪽이 막는다. */
	public void update(SeatGradeName name, Integer price) {
		this.name = name;
		this.price = price;
	}
}
