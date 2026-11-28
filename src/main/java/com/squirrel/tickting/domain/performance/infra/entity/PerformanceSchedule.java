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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

	/** 회차를 취소한 사유. 취소한 회차에만 값이 있고, 회차 취소 환불의 상세 사유가 된다. */
	@Column(length = 500)
	private String cancelReason;

	/** 판매 좌석을 만든 시각. 비어 있으면 아직 안 만든 회차다. 끝난 회차의 좌석을 지워도 값은 남아 다시 만들지 않는다. */
	@Column
	private LocalDateTime seatsGeneratedAt;

	/**
	 * 연기 이력(읽기 전용). 최신 이력이 먼저다. 연기 무료 취소는 가장 최근 이력만 보고 판단한다.
	 * 연기 이력은 SchedulePostponement 를 직접 저장해서 만들고, 이 목록으로는 저장하지 않는다.
	 */
	@OneToMany(mappedBy = "schedule")
	@OrderBy("id DESC")
	private List<SchedulePostponement> postponements;

	/** 가장 최근 연기 이력. 지연 로딩이라 트랜잭션 안에서 불러야 한다. */
	public Optional<SchedulePostponement> findLatestPostponement() {
		return postponements.stream().findFirst();
	}
}
