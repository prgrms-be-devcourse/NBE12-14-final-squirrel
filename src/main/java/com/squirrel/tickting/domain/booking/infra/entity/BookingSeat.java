package com.squirrel.tickting.domain.booking.infra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * 예매 좌석. 구역·열·번호·등급·가격을 복사(스냅샷)해서 가진다.
 * 끝난 회차의 판매 좌석을 지워도 "어떤 좌석을 얼마에 샀는지"가 남아야 해서 판매 좌석 번호는 비어 있을 수 있다.
 * 행이 많은 테이블이라 연관관계 없이 번호만 가진다.
 * 활성 좌석에만 걸린 부분 UNIQUE 인덱스(uq_booking_seat_active)가 중복 판매를 DB 에서 마지막으로 막는다.
 */
@Entity
@Table(name = "booking_seat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BookingSeat {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 예매 번호 (booking) */
	@Column(nullable = false)
	private Long bookingId;

	/** 판매 좌석 번호 (schedule_seat). 끝난 회차의 좌석을 지우면 비게 된다. */
	@Column
	private Long scheduleSeatId;

	@Column(nullable = false, length = 50)
	private String zoneName;

	@Column(nullable = false, length = 30)
	private String rowLabel;

	@Column(nullable = false, length = 30)
	private String seatNumber;

	@Column(nullable = false, length = 50)
	private String gradeName;

	@Column(nullable = false)
	private Integer price;

	/** 좌석 1장당 예매 수수료 */
	@Column(nullable = false)
	private Integer bookingFee;

	/** 취소하면 false. 취소된 좌석은 다시 팔릴 수 있어서 판매 좌석 번호에 단순 UNIQUE 를 걸 수 없다. */
	@Column(nullable = false)
	private boolean active;

	@Column
	private LocalDateTime canceledAt;

	/** 이 좌석을 취소할 때 계산한 수수료 */
	@Column
	private Integer cancelFee;

	/** 이 좌석을 취소하며 만든 환불 번호 (refund) */
	@Column
	private Long refundId;
}
