package com.squirrel.tickting.domain.booking.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import com.squirrel.tickting.domain.booking.infra.repository.BookingRepository;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.global.exception.ExceptionCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Builder;

/**
 * 판매자의 예매 조회(SLR-18·23·24)와 발송 처리(SLR-40~42) 응답. 예매자용 응답과 모양이 달라서 이름을 구분했다.
 * 판매자는 수수료 없이 "얼마짜리 표가 몇 장, 최종 얼마"만 본다. 처음 결제 총액·환불액·주문자 이메일은 담지 않는다.
 */
public class SellerBookingResponse {

	private SellerBookingResponse() {
	}

	@Builder
	@Schema(name = "SellerBookingItem", description = "회차별 예매 목록 항목")
	public record Item(
		@Schema(description = "예매 번호 (내부)")
		Long bookingId,
		@Schema(description = "예매 번호 (예매자에게 보이는 번호). 현장 확인에 쓴다", example = "20261125KCZB03R")
		String bookingNo,
		@Schema(description = "예매 상태", example = "PAID")
		BookingStatus status,
		@Schema(description = "주문자 이름. 대표·담당자 모두 그대로 준다")
		String ordererName,
		@Schema(description = "주문자 전화번호. 대표·담당자 모두 그대로 준다")
		String ordererPhone,
		@Schema(description = "수령 방법", example = "ONSITE")
		ReceiveMethod receiveType,
		@Schema(description = "배송 정보. 배송(DELIVERY) 예매만 있고 현장 수령은 null")
		Delivery delivery,
		@Schema(description = "남은 표. 등급·가격별, 가격 높은 순")
		List<Ticket> tickets,
		@Schema(description = "취소한 표. 등급·가격별, 가격 높은 순. 처음 표값이며 취소 수수료·환불과 무관")
		List<Ticket> canceledTickets,
		@Schema(description = "남은 표 장수")
		int seatCount,
		@Schema(description = "취소한 표 장수")
		int canceledSeatCount,
		@Schema(description = "남은 표값 합계 (수수료 없음)")
		int ticketAmount,
		@Schema(description = "예매 일시")
		LocalDateTime createdAt
	) {

		/** 예매의 읽기 전용 좌석 연관(batch fetch)에서 꺼내므로 트랜잭션 안에서 만든다. */
		public static Item from(Booking booking) {
			List<Ticket> remaining = Ticket.group(booking.getBookingSeats(), true);
			List<Ticket> canceled = Ticket.group(booking.getBookingSeats(), false);
			return Item.builder()
				.bookingId(booking.getId())
				.bookingNo(booking.getBookingNo())
				.status(booking.getStatus())
				.ordererName(booking.getOrdererName())
				.ordererPhone(booking.getOrdererPhone())
				.receiveType(booking.getReceiveType())
				.delivery(booking.getReceiveType() == ReceiveMethod.DELIVERY ? Delivery.from(booking) : null)
				.tickets(remaining)
				.canceledTickets(canceled)
				.seatCount(remaining.stream().mapToInt(Ticket::count).sum())
				.canceledSeatCount(canceled.stream().mapToInt(Ticket::count).sum())
				.ticketAmount(remaining.stream().mapToInt(Ticket::amount).sum())
				.createdAt(booking.getCreatedAt())
				.build();
		}
	}

	/**
	 * 판매자의 사업체 전체 예매 목록 항목이자 예매 상세. 여러 공연이 섞여서 회차별 항목(Item)에 공연·회차를 더했다.
	 * 회차별 목록과 같이 수수료 없는 표값만 담고, 처음 결제 총액·환불액·주문자 이메일은 담지 않는다.
	 */
	@Builder
	@Schema(name = "SellerBookingSummary", description = "판매자 예매 목록 항목·상세")
	public record Summary(
		@Schema(description = "예매 번호 (내부)")
		Long bookingId,
		@Schema(description = "예매 번호 (예매자에게 보이는 번호). 현장 확인에 쓴다", example = "20261125KCZB03R")
		String bookingNo,
		@Schema(description = "예매 상태", example = "PAID")
		BookingStatus status,
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 제목", example = "가상 콘서트")
		String performanceTitle,
		@Schema(description = "회차 번호")
		Long scheduleId,
		@Schema(description = "회차 일시. 연기되면 바뀐 일시")
		LocalDateTime scheduleStartAt,
		@Schema(description = "주문자 이름. 대표·담당자 모두 그대로 준다")
		String ordererName,
		@Schema(description = "주문자 전화번호. 대표·담당자 모두 그대로 준다")
		String ordererPhone,
		@Schema(description = "수령 방법", example = "ONSITE")
		ReceiveMethod receiveType,
		@Schema(description = "배송 정보. 배송(DELIVERY) 예매만 있고 현장 수령은 null")
		Delivery delivery,
		@Schema(description = "남은 표. 등급·가격별, 가격 높은 순")
		List<Ticket> tickets,
		@Schema(description = "취소한 표. 등급·가격별, 가격 높은 순. 처음 표값이며 취소 수수료·환불과 무관")
		List<Ticket> canceledTickets,
		@Schema(description = "남은 표 장수")
		int seatCount,
		@Schema(description = "취소한 표 장수")
		int canceledSeatCount,
		@Schema(description = "남은 표값 합계 (수수료 없음)")
		int ticketAmount,
		@Schema(description = "예매 일시")
		LocalDateTime createdAt
	) {

		/** 공연·회차·좌석은 예매의 읽기 전용 연관(batch fetch)에서 꺼내므로 트랜잭션 안에서 만든다. */
		public static Summary from(Booking booking) {
			List<Ticket> remaining = Ticket.group(booking.getBookingSeats(), true);
			List<Ticket> canceled = Ticket.group(booking.getBookingSeats(), false);
			return Summary.builder()
				.bookingId(booking.getId())
				.bookingNo(booking.getBookingNo())
				.status(booking.getStatus())
				.performanceId(booking.getPerformanceId())
				.performanceTitle(booking.getPerformance().getTitle())
				.scheduleId(booking.getScheduleId())
				.scheduleStartAt(booking.getSchedule().getStartAt())
				.ordererName(booking.getOrdererName())
				.ordererPhone(booking.getOrdererPhone())
				.receiveType(booking.getReceiveType())
				.delivery(booking.getReceiveType() == ReceiveMethod.DELIVERY ? Delivery.from(booking) : null)
				.tickets(remaining)
				.canceledTickets(canceled)
				.seatCount(remaining.stream().mapToInt(Ticket::count).sum())
				.canceledSeatCount(canceled.stream().mapToInt(Ticket::count).sum())
				.ticketAmount(remaining.stream().mapToInt(Ticket::amount).sum())
				.createdAt(booking.getCreatedAt())
				.build();
		}
	}

	@Schema(name = "SellerBookingDelivery", description = "배송 정보")
	public record Delivery(
		String recipientName,
		String recipientPhone,
		String recipientAddress,
		@Schema(description = "택배사 코드. 발송 전이면 null", example = "CJ")
		String carrier,
		@Schema(description = "송장번호. 발송 전이면 null")
		String trackingNo,
		@Schema(description = "발송 일시(처음 송장을 넣은 시각). 발송 전이면 null")
		LocalDateTime shippedAt
	) {

		static Delivery from(Booking booking) {
			return new Delivery(booking.getRecipientName(), booking.getRecipientPhone(), booking.getRecipientAddress(),
				booking.getCarrier(), booking.getTrackingNo(), booking.getShippedAt());
		}
	}

	@Builder
	@Schema(name = "SellerDeliveryTarget", description = "배송 대상(발송할 수 있는 예매) 한 건")
	public record DeliveryTarget(
		Long bookingId,
		@Schema(description = "예매 번호. 송장 일괄 입력에서 예매를 찾는 값", example = "20261125KCZB03R")
		String bookingNo,
		Long scheduleId,
		LocalDateTime scheduleStartAt,
		String recipientName,
		String recipientPhone,
		String recipientAddress,
		@Schema(description = "남은 표 장수. 봉투에 넣을 티켓 수")
		int seatCount,
		String carrier,
		String trackingNo,
		LocalDateTime shippedAt
	) {

		public static DeliveryTarget from(BookingRepository.DeliveryTargetRow row) {
			return DeliveryTarget.builder()
				.bookingId(row.getBookingId())
				.bookingNo(row.getBookingNo())
				.scheduleId(row.getScheduleId())
				.scheduleStartAt(row.getScheduleStartAt())
				.recipientName(row.getRecipientName())
				.recipientPhone(row.getRecipientPhone())
				.recipientAddress(row.getRecipientAddress())
				.seatCount(row.getSeatCount().intValue())
				.carrier(row.getCarrier())
				.trackingNo(row.getTrackingNo())
				.shippedAt(row.getShippedAt())
				.build();
		}
	}

	@Schema(name = "SellerShipmentResult", description = "송장 일괄 입력 결과")
	public record ShipmentResult(
		@Schema(description = "올린 건수")
		int total,
		@Schema(description = "반영한 건수")
		int succeeded,
		@Schema(description = "반영하지 못한 행")
		List<ShipmentFailure> failed
	) {
	}

	@Schema(name = "SellerShipmentFailure", description = "송장을 반영하지 못한 행")
	public record ShipmentFailure(
		@Schema(description = "items 에서의 위치(0부터)")
		int row,
		String bookingNo,
		@Schema(description = "BOOKING006 없는 예매, BOOKING014 배송 예매가 아님, BOOKING015 발송할 수 없는 상태")
		String code,
		String message
	) {

		public static ShipmentFailure of(int row, String bookingNo, ExceptionCode code) {
			return new ShipmentFailure(row, bookingNo, code.getCode(), code.getMessage());
		}
	}

	@Schema(name = "SellerBookingTicket", description = "등급·가격별 표")
	public record Ticket(
		@Schema(description = "등급 코드. 예매 때 저장한 값", example = "R")
		String gradeName,
		@Schema(description = "표 한 장 값(원). 예매 때 저장한 값이라 나중에 등급 가격이 바뀌어도 그대로", example = "154000")
		int price,
		@Schema(description = "장수")
		int count,
		@Schema(description = "표값 합계 = 가격 × 장수")
		int amount
	) {

		/** 남은(active) 또는 취소한 좌석을 등급·가격별로 묶는다. 가격 높은 순, 같으면 등급 코드순이다. */
		static List<Ticket> group(List<BookingSeat> seats, boolean active) {
			return seats.stream()
				.filter(seat -> seat.isActive() == active)
				.collect(Collectors.groupingBy(seat -> new Kind(seat.getGradeName(), seat.getPrice()),
					Collectors.counting()))
				.entrySet().stream()
				.map(entry -> {
					int count = entry.getValue().intValue();
					return new Ticket(entry.getKey().gradeName(), entry.getKey().price(), count,
						entry.getKey().price() * count);
				})
				.sorted(Comparator.comparingInt(Ticket::price).reversed().thenComparing(Ticket::gradeName))
				.toList();
		}

		/** 묶는 기준(등급과 가격). */
		private record Kind(String gradeName, int price) {
		}
	}
}
