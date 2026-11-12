package com.squirrel.tickting.domain.performance.infra.entity;

import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공연 회차. 판매 상태를 회차가 가진다. (한 회차만 연기하는 경우를 표현하려고)
 * 회차를 추가할 때는 이 행만 만들고, 판매 좌석(schedule_seat)은 판매 오픈 전날에 만든다.
 */
@Entity
@Table(name = "performance_schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PerformanceSchedule extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "performance_id", nullable = false)
	private Performance performance;

	@Column(nullable = false)
	private LocalDateTime startAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ScheduleStatus status;

	/** 일시 중지일 때만 값이 있다. */
	@Column
	private String pauseReason;

	/** 판매 좌석을 만든 시각. 비어 있으면 아직 안 만든 회차다. 끝난 회차의 좌석을 지워도 값은 남아 다시 만들지 않는다. */
	@Column
	private LocalDateTime seatsGeneratedAt;
}
