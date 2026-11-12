package com.squirrel.tickting.domain.payment.infra.entity;

import com.squirrel.tickting.domain.payment.type.RefundReasonType;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
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
 * 환불. 성공할 수도 실패할 수도 있고 여러 번 일어날 수 있어서 예매와 따로 상태를 가진다.
 * 토스에 요청할 때 항상 같은 idempotencyKey 를 보내서, 응답이 끊긴 뒤 다시 요청해도 이중 환불이 되지 않게 한다.
 */
@Entity
@Table(name = "refund")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Refund {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "payment_id", nullable = false)
	private Payment payment;

	/** 예매 번호 (booking) */
	@Column(nullable = false)
	private Long bookingId;

	/** 이번 환불로 돌려주는 금액 */
	@Column(nullable = false)
	private Integer amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private RefundReasonType reasonType;

	@Column(length = 500)
	private String reasonDetail;

	/** 환불 행을 만들 때 정하는 UUID. 중복 불가 */
	@Column(nullable = false, length = 100)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RefundStatus status;

	/** 자동 재시도는 3회까지 */
	@Builder.Default
	@Column(nullable = false)
	private Integer retryCount = 0;

	@Column(length = 500)
	private String failReason;

	@Column(nullable = false)
	private LocalDateTime requestedAt;

	@Column
	private LocalDateTime completedAt;
}
