package com.squirrel.tickting.domain.payment.infra.entity;

import com.squirrel.tickting.domain.payment.type.PaymentStatus;
import com.squirrel.tickting.global.entity.CreatedTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 결제. 결제 시도가 여러 번일 수 있어서 예매 하나에 여러 건이 생길 수 있다. */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Payment extends CreatedTimeEntity {

	private static final int FAIL_REASON_MAX_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 예매 번호 (booking) */
	@Column(name = "booking_id", nullable = false)
	private Long bookingId;

	/** 토스에 보내는 orderId. 중복 불가이며 승인 요청이 두 번 처리되지 않게 하는 키로도 쓴다. */
	@Column(nullable = false, length = 100)
	private String orderId;

	/**
	 * 승인을 시작할 때 승인 요청에 온 값을 넣고, 승인에 성공하면 토스가 돌려준 값으로 다시 쓴다.
	 * 승인 응답을 잃은 결제를 보정 작업이 이 키로 토스에 조회해서, 승인 성공 전에도 있어야 한다.
	 * (결제위젯 시크릿 키로는 주문 ID 조회를 부를 수 없고 결제 키 조회만 된다)
	 */
	@Column(length = 200)
	private String paymentKey;

	/** 카드, 간편결제 등. 승인 후 기록만 한다. */
	@Column(length = 50)
	private String method;

	/** 서버가 계산한 금액. 클라이언트가 보낸 금액은 이 값과 비교만 한다. */
	@Column(nullable = false)
	private Integer amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PaymentStatus status;

	@Column(length = 500)
	private String failReason;

	@Column
	private LocalDateTime approvedAt;

	/**
	 * 토스에 승인을 요청하기 직전의 시각. 보정 작업이 "승인을 시작한 지 오래된 결제"를 고르는 데 쓴다.
	 * 결제를 준비한 시각(createdAt)과 따로 둔 이유: 준비한 지 오래된 결제를 방금 승인 중으로 바꿨을 때 지금 응답을 기다리는 승인을 건드리지 않으려는 것이다.
	 */
	@Column
	private LocalDateTime processingStartedAt;

	/**
	 * 토스에 승인을 요청하기 직전에 부른다. 이 상태가 되면 같은 결제의 다른 승인 요청은 막힌다.
	 * 결제 준비(READY) 상태에서만 시작할 수 있다.
	 *
	 * @param paymentKey 승인 요청에 온 결제 키. 사용자가 보낸 값이라, 보정 작업은 이 키로 조회한 결제의 주문 ID 가 이 결제의 것인지 확인한다
	 */
	public void startProcessing(String paymentKey, LocalDateTime now) {
		requireStatus(PaymentStatus.READY);
		this.status = PaymentStatus.PROCESSING;
		this.processingStartedAt = now;
		this.paymentKey = paymentKey;
	}

	/**
	 * 보정 작업이 토스에 조회해서 결제되지 않았다고 확인한 결제를 실패로 닫는다. 승인을 시작하지 않은 준비(READY) 건과
	 * 승인 중(PROCESSING)으로 남은 건 모두 닫을 수 있다. 사유는 컬럼 길이에 맞게 자른다.
	 */
	public void abandon(String reason) {
		if (this.status != PaymentStatus.READY && this.status != PaymentStatus.PROCESSING) {
			throw new IllegalStateException("준비 또는 승인 중인 결제만 닫을 수 있다. 현재: " + this.status);
		}
		this.status = PaymentStatus.FAILED;
		this.failReason = reason != null && reason.length() > FAIL_REASON_MAX_LENGTH
			? reason.substring(0, FAIL_REASON_MAX_LENGTH)
			: reason;
	}

	/** 토스 승인에 성공했을 때. 결제 키·수단·승인 시각은 승인 뒤에만 생긴다. */
	public void complete(String paymentKey, String method, LocalDateTime approvedAt) {
		requireStatus(PaymentStatus.PROCESSING);
		this.status = PaymentStatus.DONE;
		this.paymentKey = paymentKey;
		this.method = method;
		this.approvedAt = approvedAt;
	}

	/** 토스가 승인을 거절했을 때. 사유는 컬럼 길이에 맞게 자른다. */
	public void fail(String reason) {
		requireStatus(PaymentStatus.PROCESSING);
		this.status = PaymentStatus.FAILED;
		this.failReason = reason != null && reason.length() > FAIL_REASON_MAX_LENGTH
			? reason.substring(0, FAIL_REASON_MAX_LENGTH)
			: reason;
	}

	/**
	 * 환불을 반영한다. 남은 좌석이 없으면 전액 환불(CANCELED), 있으면 일부 환불(PARTIAL_CANCELED)이다.
	 * 승인된 결제(DONE·PARTIAL_CANCELED)만 환불할 수 있다. 토스 환불의 성공·실패는 refund 가 가지므로 여기서는 보지 않는다.
	 */
	public void applyCancel(boolean fullyCanceled) {
		if (this.status != PaymentStatus.DONE && this.status != PaymentStatus.PARTIAL_CANCELED) {
			throw new IllegalStateException("승인된 결제만 환불할 수 있다. 현재: " + this.status);
		}
		this.status = fullyCanceled ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED;
	}

	private void requireStatus(PaymentStatus expected) {
		if (this.status != expected) {
			throw new IllegalStateException("결제 상태가 " + expected + " 여야 한다. 현재: " + this.status);
		}
	}
}
