package com.squirrel.tickting.domain.booking.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.type.PaymentStatus;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.Builder;

/** 예매자(User) 화면의 예매 응답. 예매 도메인을 함께 쓰는 C(BookingResponse)·A와 파일을 나누려고 역할 이름을 붙인다. */
public class UserBookingResponse {

	private UserBookingResponse() {
	}

	/** 내 예매 목록의 한 항목. (API 명세 BKG-04) 매수와 금액은 처음 예매 기준이고, 남은 좌석·환불 금액은 상세에서 본다. */
	@Builder
	@Schema(name = "UserBookingSummary", description = "내 예매 목록 항목")
	public record Summary(
		@Schema(description = "예매 번호 (내부)")
		Long bookingId,
		@Schema(description = "예매 번호 (사용자에게 보이는 번호)", example = "20261125KCZB03R")
		String bookingNo,
		@Schema(description = "예매 상태")
		BookingStatus status,
		@Schema(description = "공연 제목. 공연 정보가 없으면 null", example = "가상 콘서트")
		String performanceTitle,
		@Schema(description = "포스터 이미지 주소. 공연 정보가 없으면 null")
		String posterUrl,
		@Schema(description = "회차 일시. 연기되면 바뀐 일시. 회차 정보가 없으면 null")
		LocalDateTime scheduleStartAt,
		@Schema(description = "처음 예매한 좌석 수 (취소된 좌석 포함)", example = "2")
		Integer seatCount,
		@Schema(description = "처음 결제 금액 (취소해도 바뀌지 않음)", example = "312000")
		Integer totalAmount,
		@Schema(description = "예매한 일시")
		LocalDateTime createdAt
	) {

		/**
		 * 공연·회차·좌석 수는 예매의 읽기 전용 연관에서 꺼낸다. 지연 로딩이라 트랜잭션 안에서 불러야 한다.
		 * 회차는 연기되면 바뀐 일시가 보이도록 예매 때 복사하지 않고 지금 값을 읽는다. 공연이 취소돼도 예매 내역에는 제목이 남는다.
		 */
		public static Summary from(Booking booking) {
			return Summary.builder()
				.bookingId(booking.getId())
				.bookingNo(booking.getBookingNo())
				.status(booking.getStatus())
				.performanceTitle(booking.getPerformance().getTitle())
				.posterUrl(booking.getPerformance().getPosterUrl())
				.scheduleStartAt(booking.getSchedule().getStartAt())
				.seatCount(booking.getBookingSeats().size())
				.totalAmount(booking.getTotalAmount())
				.createdAt(booking.getCreatedAt())
				.build();
		}
	}

	/**
	 * 내 예매 상세. (API 명세 BKG-05) 결제 화면도 결제 대기 예매를 이 응답으로 그린다.
	 * 취소 가능 여부·지금 수수료율은 예매 취소(BKG-06)와 같은 계산기에서 받아, 화면에 보인 수수료와 실제 환불이 어긋나지 않는다.
	 */
	@Builder
	@Schema(name = "UserBookingDetail", description = "내 예매 상세")
	public record Detail(
		@Schema(description = "예매 번호 (내부)")
		Long bookingId,
		@Schema(description = "예매 번호 (사용자에게 보이는 번호)", example = "20261125KCZB03R")
		String bookingNo,
		@Schema(description = "예매 상태")
		BookingStatus status,
		@Schema(description = "공연")
		PerformanceInfo performance,
		@Schema(description = "회차. 연기되면 바뀐 일시")
		ScheduleInfo schedule,
		@Schema(description = "수령 방법")
		ReceiveMethod receiveType,
		@Schema(description = "주문자")
		Orderer orderer,
		@Schema(description = "배송 정보. 배송(DELIVERY)이 아니면 null")
		Delivery delivery,
		@Schema(description = "예매한 좌석 전부 (취소된 좌석 포함)")
		List<Seat> seats,
		@Schema(description = "티켓 금액 합계", example = "308000")
		Integer ticketAmount,
		@Schema(description = "예매 수수료 합계", example = "4000")
		Integer bookingFeeAmount,
		@Schema(description = "처음 결제 금액 (취소해도 바뀌지 않음)", example = "312000")
		Integer totalAmount,
		@Schema(description = "환불된 금액. 완료된 환불만 더하고 취소한 적이 없으면 0", example = "0")
		Integer refundedAmount,
		@Schema(description = "승인된 결제. 결제 대기·만료된 예매는 null")
		PaymentInfo payment,
		@Schema(description = "예매 당시 취소 규정 구간. 공연에서 먼 구간부터")
		List<CancelRule> cancelPolicy,
		@Schema(description = "지금 취소할 수 있는지. 결제 완료·일부 취소이고 취소 규정상 가능한 기간일 때만 true")
		Boolean cancelable,
		@Schema(description = "지금 취소하면 적용되는 수수료율(%). 취소할 수 없으면 null", example = "10")
		Integer currentFeeRate,
		@Schema(description = "결제 제한 시각. 결제 대기(PENDING)일 때만 있고 나머지는 null")
		LocalDateTime expiresAt,
		@Schema(description = "예매한 일시")
		LocalDateTime createdAt,
		@Schema(description = "전체 취소 일시. 전부 취소됐을 때만 있음")
		LocalDateTime canceledAt
	) {

		/**
		 * 공연·회차·좌석·결제·환불은 예매의 읽기 전용 연관에서 꺼낸다. 지연 로딩이라 트랜잭션 안에서 불러야 한다.
		 *
		 * @param cancelable     지금 취소할 수 있는지. 예매 취소(BKG-06)와 같은 계산기에서 받는다
		 * @param currentFeeRate 지금 취소하면 적용되는 수수료율. 취소할 수 없으면 null
		 */
		public static Detail from(Booking booking, List<CancelRule> cancelPolicy, boolean cancelable,
			Integer currentFeeRate) {
			return Detail.builder()
				.bookingId(booking.getId())
				.bookingNo(booking.getBookingNo())
				.status(booking.getStatus())
				.performance(PerformanceInfo.from(booking.getPerformance()))
				.schedule(ScheduleInfo.from(booking.getSchedule()))
				.receiveType(booking.getReceiveType())
				.orderer(new Orderer(booking.getOrdererName(), booking.getOrdererPhone(), booking.getOrdererEmail()))
				.delivery(booking.getReceiveType() == ReceiveMethod.DELIVERY ? Delivery.from(booking) : null)
				.seats(booking.getBookingSeats().stream().map(Seat::from).toList())
				.ticketAmount(booking.getTicketAmount())
				.bookingFeeAmount(booking.getBookingFeeAmount())
				.totalAmount(booking.getTotalAmount())
				.refundedAmount(refundedAmount(booking))
				.payment(approvedPayment(booking))
				.cancelPolicy(cancelPolicy)
				.cancelable(cancelable)
				.currentFeeRate(currentFeeRate)
				.expiresAt(booking.getStatus() == BookingStatus.PENDING ? booking.getExpiresAt() : null)
				.createdAt(booking.getCreatedAt())
				.canceledAt(booking.getCanceledAt())
				.build();
		}

		/**
		 * 실제로 돌려준 금액. 완료(DONE)된 환불만 더한다. 요청 중·실패한 환불은 아직 돈이 돌아가지 않았다.
		 * 취소한 적이 없으면 0 이다.
		 */
		private static int refundedAmount(Booking booking) {
			return booking.getRefunds().stream()
				.filter(refund -> refund.getStatus() == RefundStatus.DONE)
				.mapToInt(Refund::getAmount)
				.sum();
		}

		/** 승인된 결제 중 가장 최근 것. 결제 대기·만료된 예매처럼 승인된 결제가 없으면 null 이다. */
		private static PaymentInfo approvedPayment(Booking booking) {
			return booking.getPayments().stream()
				.filter(payment -> payment.getStatus().isApproved())
				.max(Comparator.comparing(Payment::getId))
				.map(PaymentInfo::from)
				.orElse(null);
		}
	}

	@Schema(name = "UserBookingPerformance", description = "예매한 공연")
	public record PerformanceInfo(
		Long performanceId,
		@Schema(example = "가상 콘서트")
		String title,
		String posterUrl
	) {

		static PerformanceInfo from(Performance performance) {
			return new PerformanceInfo(performance.getId(), performance.getTitle(), performance.getPosterUrl());
		}
	}

	@Schema(name = "UserBookingSchedule", description = "예매한 회차. 연기되면 바뀐 일시")
	public record ScheduleInfo(
		Long scheduleId,
		LocalDateTime startAt,
		ScheduleStatus status
	) {

		static ScheduleInfo from(PerformanceSchedule schedule) {
			return new ScheduleInfo(schedule.getId(), schedule.getStartAt(), schedule.getStatus());
		}
	}

	@Schema(name = "UserBookingOrderer", description = "주문자")
	public record Orderer(
		@Schema(example = "홍길동")
		String name,
		@Schema(example = "01012345678")
		String phone,
		@Schema(example = "user@example.com")
		String email
	) {
	}

	@Schema(name = "UserBookingDelivery", description = "배송 정보. 송장은 발송 전이면 null")
	public record Delivery(
		String recipientName,
		String recipientPhone,
		String recipientAddress,
		String carrier,
		String trackingNo
	) {

		static Delivery from(Booking booking) {
			return new Delivery(booking.getRecipientName(), booking.getRecipientPhone(), booking.getRecipientAddress(),
				booking.getCarrier(), booking.getTrackingNo());
		}
	}

	/** 좌석 정보는 예매할 때 복사해 둔 값이라 공연장·등급이 바뀌어도 예매 당시 그대로 보인다. */
	@Schema(name = "UserBookingSeat", description = "예매한 좌석")
	public record Seat(
		Long bookingSeatId,
		@Schema(example = "F2구역")
		String zoneName,
		@Schema(example = "9열")
		String rowLabel,
		@Schema(example = "6")
		String seatNumber,
		@Schema(example = "R")
		String gradeName,
		@Schema(description = "좌석 가격", example = "154000")
		Integer price,
		@Schema(description = "예매 수수료", example = "2000")
		Integer bookingFee,
		@Schema(description = "취소되지 않은 좌석이면 true")
		boolean active,
		LocalDateTime canceledAt,
		@Schema(description = "이 좌석의 취소 수수료. 취소된 좌석만 있음")
		Integer cancelFee
	) {

		static Seat from(BookingSeat seat) {
			return new Seat(seat.getId(), seat.getZoneName(), seat.getRowLabel(), seat.getSeatNumber(),
				seat.getGradeName(), seat.getPrice(), seat.getBookingFee(), seat.isActive(), seat.getCanceledAt(),
				seat.getCancelFee());
		}
	}

	@Schema(name = "UserBookingPayment", description = "승인된 결제")
	public record PaymentInfo(
		Long paymentId,
		@Schema(example = "카드")
		String method,
		@Schema(example = "312000")
		Integer amount,
		@Schema(description = "결제 상태. 환불되면 PARTIAL_CANCELED·CANCELED")
		PaymentStatus status,
		LocalDateTime approvedAt
	) {

		static PaymentInfo from(Payment payment) {
			return new PaymentInfo(payment.getId(), payment.getMethod(), payment.getAmount(), payment.getStatus(),
				payment.getApprovedAt());
		}
	}

	/** 예매할 때 복사해 둔 취소 규정의 한 구간. 스냅샷 JSON 을 그대로 읽으므로 필드 이름이 스냅샷과 같아야 한다. */
	@Schema(name = "UserBookingCancelRule", description = "취소 규정 구간. daysTo 가 null 이면 제한 없음")
	public record CancelRule(
		Integer daysFrom,
		Integer daysTo,
		Integer feeRate,
		boolean cancelable
	) {
	}
}
