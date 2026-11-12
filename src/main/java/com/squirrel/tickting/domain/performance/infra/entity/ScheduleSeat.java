package com.squirrel.tickting.domain.performance.infra.entity;

import com.squirrel.tickting.domain.performance.type.SeatStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 회차별 판매 좌석. 동시성, 대기열, 매진 처리가 모두 이 테이블 위에서 일어나는 핵심 테이블이다.
 * 행이 매우 많아서(회차 하나에 수천 행) 연관관계 없이 번호만 가진다.
 * 상태를 바꿀 때는 SELECT ... FOR UPDATE 로 잠근 뒤 AVAILABLE 일 때만 HELD 로 바꾼다.
 */
@Entity
@Table(name = "schedule_seat")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ScheduleSeat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 회차 번호 (performance_schedule) */
	@Column(nullable = false)
	private Long scheduleId;

	/** 복사해 온 원본 좌석 번호 (venue_seat) */
	@Column(nullable = false)
	private Long venueSeatId;

	/** 구역별 조회를 조인 없이 하려고 복사해 둔 구역 번호 (venue_zone) */
	@Column(nullable = false)
	private Long zoneId;

	/** 등급 번호 (seat_grade). 판매 제외 좌석은 비어 있다. */
	@Column
	private Long gradeId;

	/** 등급 가격을 복사한 값. 판매 제외 좌석은 비어 있다. */
	@Column
	private Integer price;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private SeatStatus status;

	/** 임시 점유 중인 예매자 번호 (user_member). 점유 중일 때만 값이 있다. */
	@Column
	private Long heldBy;

	/** 점유 만료 시각. 점유 중일 때만 값이 있다. */
	@Column
	private LocalDateTime heldUntil;

	/** 낙관적 락 방식을 비교 실험할 때 쓴다. 필요하면 @Version 으로 바꾼다. 기본 방어는 FOR UPDATE 다. */
	@Builder.Default
	@Column(nullable = false)
	private Integer version = 0;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;
}
