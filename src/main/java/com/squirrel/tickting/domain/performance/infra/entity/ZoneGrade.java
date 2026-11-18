package com.squirrel.tickting.domain.performance.infra.entity;

import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
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

/**
 * 공연별 구역 설정. 아직 미설정인 상태와 의도적으로 판매하지 않는 상태를 구분한다.
 */
@Entity
@Table(name = "zone_grade")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ZoneGrade {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "performance_id", nullable = false)
	private Performance performance;

	/** 등급을 지정할 구역 번호 (venue_zone) */
	@Column(nullable = false)
	private Long zoneId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "grade_id")
	private SeatGrade grade;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ZoneAssignmentStatus assignmentStatus;

	/**
	 * 구역의 판매 상태와 지정 등급을 함께 바꾼다. 등급은 ASSIGNED 일 때만 있고 나머지는 null 이다.
	 * 따로 바꾸면 상태와 등급이 맞지 않는 중간 상태가 생길 수 있어서 한 메서드로 둔다.
	 */
	public void assign(ZoneAssignmentStatus assignmentStatus, SeatGrade grade) {
		this.assignmentStatus = assignmentStatus;
		this.grade = grade;
	}
}
