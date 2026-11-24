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

/** 취소 규정 구간. 공연 N일 전 이상 ~ N일 전 이하일 때 적용할 수수료율이다. */
@Entity
@Table(name = "cancel_policy_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CancelPolicyRule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "policy_id", nullable = false)
	private CancelPolicy policy;

	/** 공연 며칠 전부터 */
	@Column(nullable = false)
	private Integer daysFrom;

	/** 며칠 전까지. 비어 있으면 제한 없음 */
	@Column
	private Integer daysTo;

	/** 0~100 (%) */
	@Column(nullable = false)
	private Integer feeRate;

	/** false 면 이 구간(예: 당일)은 사용자 취소를 막는다. */
	@Column(nullable = false)
	private boolean cancelable;
}
