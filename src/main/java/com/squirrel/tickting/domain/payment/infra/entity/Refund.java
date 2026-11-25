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

	private static final int FAIL_REASON_MAX_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "payment_id", nullable = false)
	private Payment payment;

	/** 예매 번호 (booking). 예매의 읽기 전용 연관(Booking.refunds)이 같은 컬럼을 써서 이름을 적어 둔다. */
	@Column(name = "booking_id", nullable = false)
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

	/** 마지막으로 토스에 보낸(보내려 한) 시각. 재시도 스케줄러가 이 시각부터 일정 시간이 지난 환불만 다시 보낸다. */
	@Column(nullable = false)
	private LocalDateTime lastAttemptedAt;

	/** 토스가 환불을 마쳤을 때. */
	public void complete(LocalDateTime now) {
		requireStatus(RefundStatus.PROCESSING);
		this.status = RefundStatus.DONE;
		this.failReason = null;
		this.completedAt = now;
	}

	/**
	 * 토스가 환불을 거절했거나 결과를 알 수 없을 때. 같은 멱등키로 다시 보내면 이중 환불 없이 이어서 처리할 수 있어서
	 * 결과를 모르는 경우도 실패로 두고 재시도 대상으로 남긴다. 사유는 컬럼 길이에 맞게 자른다.
	 */
	public void fail(String reason) {
		requireStatus(RefundStatus.PROCESSING);
		this.status = RefundStatus.FAILED;
		this.failReason = reason != null && reason.length() > FAIL_REASON_MAX_LENGTH
			? reason.substring(0, FAIL_REASON_MAX_LENGTH)
			: reason;
	}

	private void requireStatus(RefundStatus expected) {
		if (this.status != expected) {
			throw new IllegalStateException("환불 상태가 " + expected + " 여야 한다. 현재: " + this.status);
		}
	}
}
