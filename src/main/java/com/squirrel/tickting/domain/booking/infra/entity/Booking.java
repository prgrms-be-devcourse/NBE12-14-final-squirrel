package com.squirrel.tickting.domain.booking.infra.entity;

import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.global.entity.BaseTimeEntity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 예매(주문서). 좌석 점유와 PENDING 예매 생성은 한 트랜잭션이다.
 * 환불의 성공·실패는 refund 가 가지므로 예매 상태에는 섞지 않는다.
 * 사용자·회차·공연은 다른 도메인이라 번호만 가진다.
 */
@Entity
@Table(name = "booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Booking extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 사용자에게 보이는 번호(날짜 + 랜덤). id 를 노출하면 남의 예매를 조회하려는 시도가 쉬워진다. */
	@Column(nullable = false, length = 30)
	private String bookingNo;

	/** 예매자 번호 (user_member) */
	@Column(nullable = false)
	private Long userId;

	/** 회차 번호 (performance_schedule) */
	@Column(nullable = false)
	private Long scheduleId;

	/** 공연 번호 (performance). 공연 단위 매수 검사에서 조인을 줄이려고 예매 때 복사해 둔다. */
	@Column(nullable = false)
	private Long performanceId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private BookingStatus status;

	/** 좌석을 점유할 때는 기본값(공연이 BOTH 면 ONSITE)을 넣고, 결제 준비 때 확정한다. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReceiveMethod receiveType;

	/** 주문자 정보는 예매 시점의 값을 복사(스냅샷)한다. 나중에 회원이 바꾸거나 탈퇴해도 남아야 한다. */
	@Column(nullable = false, length = 50)
	private String ordererName;

	@Column(nullable = false, length = 20)
	private String ordererPhone;

	@Column(nullable = false)
	private String ordererEmail;

	/** 배송일 때만 값이 있다. */
	@Column(length = 50)
	private String recipientName;

	@Column(length = 20)
	private String recipientPhone;

	@Column(length = 500)
	private String recipientAddress;

	/** 판매자가 발송 처리할 때 입력한다. */
	@Column(length = 50)
	private String carrier;

	@Column(length = 50)
	private String trackingNo;

	/** 좌석 가격 합계 */
	@Column(nullable = false)
	private Integer ticketAmount;

	/** 좌석 수 x 공연의 예매 수수료 */
	@Column(nullable = false)
	private Integer bookingFeeAmount;

	/** 최초 결제 금액. 취소해도 바뀌지 않는다. 환불된 금액은 refund 를 합쳐서 안다. */
	@Column(nullable = false)
	private Integer totalAmount;

	/** 예매 시점의 취소 규정 구간을 통째로 복사한 JSON. 읽기만 하므로 문자열로 둔다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String cancelPolicySnapshot;

	/** 결제 제한 시각 (좌석 점유 시작 + 7분) */
	@Column(nullable = false)
	private LocalDateTime expiresAt;

	/** 전부 취소됐을 때만 값이 있다. */
	@Column
	private LocalDateTime canceledAt;
}
