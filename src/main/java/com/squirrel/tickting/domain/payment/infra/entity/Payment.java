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

/** 결제. 결제 시도가 여러 번일 수 있어서 예매 하나에 여러 건이 생길 수 있다. 처음에는 Mock 으로 흐름을 만들고 나중에 토스로 바꾼다. */
@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Payment extends CreatedTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 예매 번호 (booking) */
	@Column(nullable = false)
	private Long bookingId;

	/** 토스에 보내는 orderId. 중복 불가이며 승인 요청이 두 번 처리되지 않게 하는 키로도 쓴다. */
	@Column(nullable = false, length = 100)
	private String orderId;

	/** 토스 승인 후에만 생긴다. */
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
}
