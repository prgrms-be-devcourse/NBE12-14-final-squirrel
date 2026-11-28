package com.squirrel.tickting.domain.booking.infra.entity;

import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
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
 * 사용자·회차·공연은 다른 도메인이라 저장할 때는 번호만 쓴다. 읽을 때는 아래의 읽기 전용 연관을 쓴다.
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
	@Column(name = "schedule_id", nullable = false)
	private Long scheduleId;

	/** 공연 번호 (performance). 공연 단위 매수 검사에서 조인을 줄이려고 예매 때 복사해 둔다. */
	@Column(name = "performance_id", nullable = false)
	private Long performanceId;

	/**
	 * 사업체 번호 (seller_business). 판매자의 사업체 전체 예매 목록을 공연 조인 없이 읽으려고 예매 때 공연에서 복사해 둔다.
	 * 공연의 사업체는 바뀌지 않아서 원본과 어긋나지 않는다. (idx_booking_business_created)
	 */
	@Column(name = "business_id", nullable = false)
	private Long businessId;

	/**
	 * 읽기 전용 연관. 응답을 만들 때 예매에서 바로 공연·회차·좌석·결제를 꺼내 쓰려는 것이다. (DB 에 FK 제약은 없다)
	 * 저장하거나 바꿀 때는 위의 번호(scheduleId, performanceId)를 쓰고, 이 필드들은 INSERT·UPDATE 에 들어가지 않는다.
	 * 지연 로딩이라 트랜잭션 안에서만 꺼낼 수 있고, 목록은 default_batch_fetch_size 로 묶어서 읽어서 예매마다 쿼리가 나가지 않는다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "performance_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private Performance performance;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "schedule_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private PerformanceSchedule schedule;

	/** 예매 좌석(읽기 전용). 취소된 좌석도 들어 있다. 점유·취소 처리는 이 목록이 아니라 BookingSeat 를 직접 다룬다. */
	@OneToMany
	@OrderBy("id")
	@JoinColumn(name = "booking_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private List<BookingSeat> bookingSeats;

	/** 결제(읽기 전용). 준비·실패한 건도 들어 있어서 승인된 것만 고르려면 PaymentStatus.isApproved 로 거른다. */
	@OneToMany
	@OrderBy("id")
	@JoinColumn(name = "booking_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private List<Payment> payments;

	/** 환불(읽기 전용). 요청 중·실패한 건도 들어 있어서 돌려준 금액을 셀 때는 완료(DONE)만 더한다. */
	@OneToMany
	@OrderBy("id")
	@JoinColumn(name = "booking_id", insertable = false, updatable = false,
		foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
	private List<Refund> refunds;

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

	/** 처음 송장을 넣은 시각. 송장을 고쳐도 바뀌지 않는다. 택배사·송장번호와 함께 리포지토리의 조건부 UPDATE 로만 바꾼다. */
	@Column
	private LocalDateTime shippedAt;

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

	/** 결제 승인을 시작할 때 결제 제한 시각을 늘린다. 토스를 부르는 동안 스케줄러가 좌석을 풀어 버리지 않게 하려는 것이다. */
	public void extendExpiry(LocalDateTime expiresAt) {
		this.expiresAt = expiresAt;
	}

	/**
	 * 결제 준비 때 수령 방법과 배송 정보를 확정한다. 현장 수령이면 받는 사람 정보는 비운다.
	 * 결제 준비를 다시 요청하면 이전에 넣은 배송 정보가 남지 않게 매번 통째로 바꾼다.
	 */
	public void changeReceiveInfo(ReceiveMethod receiveType, String recipientName, String recipientPhone,
		String recipientAddress) {
		boolean delivery = receiveType == ReceiveMethod.DELIVERY;
		this.receiveType = receiveType;
		this.recipientName = delivery ? recipientName : null;
		this.recipientPhone = delivery ? recipientPhone : null;
		this.recipientAddress = delivery ? recipientAddress : null;
	}
}
