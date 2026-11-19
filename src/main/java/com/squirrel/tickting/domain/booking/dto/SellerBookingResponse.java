package com.squirrel.tickting.domain.booking.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Builder;

/**
 * 판매자의 회차별 예매 응답(SLR-18). 예매자용 응답과 모양이 달라서 이름을 구분했다.
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

	@Schema(name = "SellerBookingDelivery", description = "배송 정보")
	public record Delivery(
		String recipientName,
		String recipientPhone,
		String recipientAddress
	) {

		static Delivery from(Booking booking) {
			return new Delivery(booking.getRecipientName(), booking.getRecipientPhone(), booking.getRecipientAddress());
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
