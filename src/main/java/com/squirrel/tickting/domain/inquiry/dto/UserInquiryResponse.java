package com.squirrel.tickting.domain.inquiry.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.inquiry.infra.entity.Inquiry;
import com.squirrel.tickting.domain.inquiry.infra.entity.InquiryImage;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 예매자 문의 응답. 문의 도메인을 판매자·관리자가 함께 쓰므로 역할 이름을 붙여 파일을 나눈다. */
public class UserInquiryResponse {

	private UserInquiryResponse() {
	}

	/** 문의 유형 하나. 화면의 유형 드롭다운에 쓴다. */
	@Schema(name = "UserInquiryCategory", description = "문의 유형")
	public record Category(
		@Schema(example = "CANCEL_REFUND")
		InquiryCategory code,
		@Schema(example = "취소·환불")
		String name
	) {

		public static Category from(InquiryCategory category) {
			return new Category(category, category.getLabel());
		}
	}

	@Schema(name = "UserInquiryCreated", description = "문의 작성 결과")
	public record Created(
		Long inquiryId,
		InquiryStatus status,
		LocalDateTime createdAt
	) {

		public static Created from(Inquiry inquiry) {
			return new Created(inquiry.getId(), inquiry.getStatus(), inquiry.getCreatedAt());
		}
	}

	/** 내 문의 목록의 한 항목. 예매 번호는 예매의 읽기 전용 연관에서 꺼내므로 트랜잭션 안에서 만든다. */
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
			return new Summary(inquiry.getId(), Category.from(inquiry.getCategory()), inquiry.getTitle(),
				inquiry.getStatus(), inquiry.getBooking() == null ? null : inquiry.getBooking().getBookingNo(),
				inquiry.getCreatedAt(), inquiry.getAnsweredAt());
		}
	}

	/** 내 문의 상세. 사진·예매는 문의의 읽기 전용 연관에서 꺼내므로 트랜잭션 안에서 만든다. */
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
			return new Detail(inquiry.getId(), Category.from(inquiry.getCategory()), inquiry.getTitle(),
				inquiry.getContent(), inquiry.getImages().stream().map(InquiryImage::getUrl).toList(),
				inquiry.getStatus(), inquiry.getBooking() == null ? null : BookingInfo.from(inquiry.getBooking()),
				inquiry.getAnswer(), inquiry.getAnsweredAt(), inquiry.getCreatedAt(), inquiry.getUpdatedAt());
		}
	}

	/** 문의에 연결한 예매. 공연 제목은 지금 값이다. */
	@Schema(name = "UserInquiryBooking", description = "문의에 연결한 예매")
	public record BookingInfo(
		Long bookingId,
		@Schema(example = "20261125KCZB03R")
		String bookingNo,
		@Schema(example = "가상 콘서트")
		String performanceTitle
	) {

		static BookingInfo from(Booking booking) {
			return new BookingInfo(booking.getId(), booking.getBookingNo(), booking.getPerformance().getTitle());
		}
	}
}
