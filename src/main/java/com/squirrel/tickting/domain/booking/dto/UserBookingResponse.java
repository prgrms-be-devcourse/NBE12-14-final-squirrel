package com.squirrel.tickting.domain.booking.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
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

		/** 공연·회차는 논리 FK 라 행이 없을 수 있다. 없으면 그 값만 비우고(null) 예매 내역은 그대로 보여 준다. */
		public static Summary from(Booking booking, int seatCount, Performance performance, PerformanceSchedule schedule) {
			return Summary.builder()
				.bookingId(booking.getId())
				.bookingNo(booking.getBookingNo())
				.status(booking.getStatus())
				.performanceTitle(performance == null ? null : performance.getTitle())
				.posterUrl(performance == null ? null : performance.getPosterUrl())
				.scheduleStartAt(schedule == null ? null : schedule.getStartAt())
				.seatCount(seatCount)
				.totalAmount(booking.getTotalAmount())
				.createdAt(booking.getCreatedAt())
				.build();
		}
	}
}
