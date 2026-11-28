package com.squirrel.tickting.domain.inquiry.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.inquiry.infra.entity.Inquiry;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/** 예매자 문의 응답. 문의 도메인을 판매자·관리자가 함께 쓰므로 역할 이름을 붙여 파일을 나눈다. */
public class UserInquiryResponse {

	private UserInquiryResponse() {
	}

	/** 문의 유형 하나. 화면의 유형 드롭다운에 쓴다. 판매자 문의도 같은 모양이라 함께 쓴다. */
	@Builder
	@Schema(name = "InquiryCategoryItem", description = "문의 유형")
	public record Category(
		@Schema(example = "CANCEL_REFUND")
		InquiryCategory code,
		@Schema(example = "취소·환불")
		String name
	) {

		public static Category from(InquiryCategory category) {
			return Category.builder()
				.code(category)
				.name(category.getLabel())
				.build();
		}
	}

	/** 문의 작성 결과. 판매자 문의도 같은 모양이라 함께 쓴다. */
	@Builder
	@Schema(name = "InquiryCreated", description = "문의 작성 결과")
	public record Created(
		Long inquiryId,
		InquiryStatus status,
		LocalDateTime createdAt
	) {

		public static Created from(Inquiry inquiry) {
			return Created.builder()
				.inquiryId(inquiry.getId())
				.status(inquiry.getStatus())
				.createdAt(inquiry.getCreatedAt())
				.build();
		}
	}

	/** 내 문의 목록의 한 항목. 예매 번호는 예매의 읽기 전용 연관에서 꺼내므로 트랜잭션 안에서 만든다. */
	@Builder
	@Schema(name = "UserInquirySummary", description = "내 문의 목록 항목")
	public record Summary(
		Long inquiryId,
		Category category,
		String title,
		InquiryStatus status,
		@Schema(description = "관련 예매 번호(사용자에게 보이는 번호). 고르지 않았으면 null", example = "20261125KCZB03R")
		String bookingNo,
		LocalDateTime createdAt,
		@Schema(description = "답변 일시. 답변 전이면 null")
		LocalDateTime answeredAt
	) {

		public static Summary from(Inquiry inquiry) {
			return Summary.builder()
				.inquiryId(inquiry.getId())
				.category(Category.from(inquiry.getCategory()))
				.title(inquiry.getTitle())
				.status(inquiry.getStatus())
				.bookingNo(inquiry.getBooking() == null ? null : inquiry.getBooking().getBookingNo())
				.createdAt(inquiry.getCreatedAt())
				.answeredAt(inquiry.getAnsweredAt())
				.build();
		}
	}

	/** 내 문의 상세. 예매는 문의의 읽기 전용 연관에서 꺼내므로 트랜잭션 안에서 만든다. */
	@Builder
	@Schema(name = "UserInquiryDetail", description = "내 문의 상세")
	public record Detail(
		Long inquiryId,
		Category category,
		String title,
		String content,
		@Schema(description = "첨부 사진 주소. 없으면 빈 배열")
		List<String> imageUrls,
		InquiryStatus status,
		@Schema(description = "관련 예매. 고르지 않았으면 null")
		BookingInfo booking,
		@Schema(description = "관리자 답변. 답변 전이면 null")
		String answer,
		LocalDateTime answeredAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
	) {

		public static Detail from(Inquiry inquiry) {
			return Detail.builder()
				.inquiryId(inquiry.getId())
				.category(Category.from(inquiry.getCategory()))
				.title(inquiry.getTitle())
				.content(inquiry.getContent())
				.imageUrls(inquiry.getImageUrls())
				.status(inquiry.getStatus())
				.booking(inquiry.getBooking() == null ? null : BookingInfo.from(inquiry.getBooking()))
				.answer(inquiry.getAnswer())
				.answeredAt(inquiry.getAnsweredAt())
				.createdAt(inquiry.getCreatedAt())
				.updatedAt(inquiry.getUpdatedAt())
				.build();
		}
	}

	/** 문의에 연결한 예매. 공연 제목은 지금 값이다. */
	@Builder
	@Schema(name = "UserInquiryBooking", description = "문의에 연결한 예매")
	public record BookingInfo(
		Long bookingId,
		@Schema(example = "20261125KCZB03R")
		String bookingNo,
		@Schema(example = "가상 콘서트")
		String performanceTitle
	) {

		static BookingInfo from(Booking booking) {
			return BookingInfo.builder()
				.bookingId(booking.getId())
				.bookingNo(booking.getBookingNo())
				.performanceTitle(booking.getPerformance().getTitle())
				.build();
		}
	}
}
