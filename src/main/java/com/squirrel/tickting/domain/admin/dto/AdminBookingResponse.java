package com.squirrel.tickting.domain.admin.dto;

import com.squirrel.tickting.domain.booking.dto.UserBookingResponse;
import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Builder;

public class AdminBookingResponse {

	private AdminBookingResponse() {
	}

	/** 관리자 예매 목록 항목. 누가(예매자) 어느 사업체 공연을 예매했는지 함께 보여 문의·분쟁 응대에 쓴다. */
	@Builder
	@Schema(name = "AdminBookingSummary", description = "관리자 예매 목록 항목")
	public record Summary(
		@Schema(description = "예매 번호 (내부)")
		Long bookingId,
		@Schema(description = "예매 번호 (사용자에게 보이는 번호)", example = "20261125KCZB03R")
		String bookingNo,
		@Schema(description = "예매 상태")
		BookingStatus status,
		@Schema(description = "예매자 번호")
		Long userId,
		@Schema(description = "주문자 이름")
		String ordererName,
		@Schema(description = "사업체 번호")
		Long businessId,
		@Schema(description = "공연 번호")
		Long performanceId,
		@Schema(description = "공연 제목", example = "가상 콘서트")
		String performanceTitle,
		@Schema(description = "회차 일시. 연기되면 바뀐 일시")
		LocalDateTime scheduleStartAt,
		@Schema(description = "처음 예매한 좌석 수 (취소된 좌석 포함)", example = "2")
		Integer seatCount,
		@Schema(description = "처음 결제 금액 (취소해도 바뀌지 않음)", example = "312000")
		Integer totalAmount,
		@Schema(description = "예매한 일시")
		LocalDateTime createdAt
	) {

		/** 공연·회차·좌석은 예매의 읽기 전용 연관에서 꺼내므로 트랜잭션 안에서 만든다. */
		public static Summary from(Booking booking) {
			return Summary.builder()
				.bookingId(booking.getId())
				.bookingNo(booking.getBookingNo())
				.status(booking.getStatus())
				.userId(booking.getUserId())
				.ordererName(booking.getOrdererName())
				.businessId(booking.getBusinessId())
				.performanceId(booking.getPerformanceId())
				.performanceTitle(booking.getPerformance().getTitle())
				.scheduleStartAt(booking.getSchedule().getStartAt())
				.seatCount(booking.getBookingSeats().size())
				.totalAmount(booking.getTotalAmount())
				.createdAt(booking.getCreatedAt())
				.build();
		}
	}

	/**
	 * 관리자 예매 상세. 예매 내용은 예매자 상세와 같은 모양(결제·환불·취소 규정 포함)을 그대로 쓰고,
	 * 예매자 화면에는 필요 없는 예매자·사업체 번호만 더한다. 같은 내용을 두 벌로 만들면 한쪽만 고쳐질 수 있어서다.
	 */
	@Schema(name = "AdminBookingDetail", description = "관리자 예매 상세")
	public record Detail(
		@Schema(description = "예매자 번호")
		Long userId,
		@Schema(description = "사업체 번호")
		Long businessId,
		@Schema(description = "예매 내용 (예매자 상세와 같은 모양)")
		UserBookingResponse.Detail booking
	) {

		public static Detail of(Booking booking, UserBookingResponse.Detail detail) {
			return new Detail(booking.getUserId(), booking.getBusinessId(), detail);
		}
	}
}
