package com.squirrel.tickting.domain.venue.infra.entity;

import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공연장. 관리자가 만들고 판매자가 공연을 등록하면서 고른다. 삭제하지 않고 status 로 사용 중지한다. */
@Entity
@Table(name = "venue")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Venue extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false)
	private String address;

	/** 서울, 경기 등. 지역별 공연 조회에 쓴다. */
	@Column(nullable = false, length = 50)
	private String region;

	/** 좌석 생성 스크립트가 만든 좌석 수를 세서 넣는다. */
	@Column(nullable = false)
	private Integer totalSeats;

	/** 프론트가 도면을 고르는 이름표. 비어 있으면 기본 격자 도면 */
	@Column(length = 50)
	private String layoutKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private VenueStatus status;
}
