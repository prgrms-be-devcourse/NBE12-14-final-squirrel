package com.squirrel.tickting.domain.performance.infra.entity;

import com.squirrel.tickting.global.entity.CreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 회차 연기 이력. 한 회차에 연기가 여러 번 쌓일 수 있고, 무료 취소 판단은 가장 최근 이력만 본다. */
@Entity
@Table(name = "schedule_postponement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SchedulePostponement extends CreatedTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "schedule_id", nullable = false)
	private PerformanceSchedule schedule;

	@Column(nullable = false)
	private LocalDateTime oldStartAt;

	/** 비어 있으면 새 일정 미정. 새 일정이 정해지면 값을 채운다. */
	@Column
	private LocalDateTime newStartAt;

	@Column(nullable = false, length = 500)
	private String reason;

	/** 이 시각까지는 수수료 없이 취소할 수 있다. 판매자가 입력하고 기본값은 연기일 + 14일이다. */
	@Column(nullable = false)
	private LocalDateTime freeCancelUntil;

	/** 연기를 처리한 판매자 계정 번호 (seller_member) */
	@Column(nullable = false)
	private Long changedBy;
}
