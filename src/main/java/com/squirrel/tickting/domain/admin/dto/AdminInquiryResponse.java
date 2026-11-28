package com.squirrel.tickting.domain.admin.dto;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.inquiry.infra.entity.Inquiry;
import com.squirrel.tickting.domain.inquiry.infra.entity.InquiryHistory;
import com.squirrel.tickting.domain.inquiry.type.InquiryAction;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import com.squirrel.tickting.global.security.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

public class AdminInquiryResponse {

	private AdminInquiryResponse() {
	}

	/** 관리자 문의 목록 항목. 문의 행의 값만 써서(작성자는 작성 때 복사한 이름·이메일) 항목마다 쿼리가 나가지 않는다. */
	@Builder
	@Schema(name = "AdminInquirySummary", description = "관리자 문의 목록 항목")
	public record Summary(
		Long inquiryId,
		@Schema(description = "작성자 종류 (USER 예매자 / SELLER 판매자)")
		AccountType authorType,
		@Schema(description = "작성 때의 작성자 이름")
		String authorName,
		@Schema(description = "작성 때의 작성자 이메일")
		String authorEmail,
		InquiryCategory category,
		@Schema(example = "취소·환불")
		String categoryName,
		String title,
		InquiryStatus status,
		LocalDateTime createdAt,
		@Schema(description = "답변 일시. 답변 전이면 null")
		LocalDateTime answeredAt,
		@Schema(description = "작성자가 삭제한 일시. 삭제하지 않았으면 null")
		LocalDateTime deletedAt
	) {

		public static Summary from(Inquiry inquiry) {
			return Summary.builder()
				.inquiryId(inquiry.getId())
				.authorType(inquiry.getAuthorType())
				.authorName(inquiry.getAuthorName())
				.authorEmail(inquiry.getAuthorEmail())
				.category(inquiry.getCategory())
				.categoryName(inquiry.getCategory().getLabel())
				.title(inquiry.getTitle())
				.status(inquiry.getStatus())
				.createdAt(inquiry.getCreatedAt())
				.answeredAt(inquiry.getAnsweredAt())
				.deletedAt(inquiry.getDeletedAt())
				.build();
		}
	}

	/** 관리자 문의 상세. 예매는 문의의 읽기 전용 연관에서 꺼내므로 서비스의 트랜잭션 안에서 만든다. */
	@Builder
	@Schema(name = "AdminInquiryDetail", description = "관리자 문의 상세 (이력 포함)")
	public record Detail(
		Long inquiryId,
		AccountType authorType,
		@Schema(description = "작성자 번호. 예매자면 예매자 번호, 판매자면 판매자 계정 번호")
		Long authorId,
		@Schema(description = "판매자 문의의 사업체 번호. 예매자 문의면 null")
		Long businessId,
		String authorName,
		String authorEmail,
		InquiryCategory category,
		String categoryName,
		String title,
		String content,
		@Schema(description = "첨부 사진 주소. 없으면 빈 배열")
		List<String> imageUrls,
		InquiryStatus status,
		@Schema(description = "관련 예매. 고르지 않았으면 null")
		BookingInfo booking,
		@Schema(description = "답변. 답변 전이면 null")
		String answer,
		@Schema(description = "답변한 관리자 번호")
		Long answeredBy,
		LocalDateTime answeredAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt,
		@Schema(description = "작성자가 삭제한 일시. 삭제하지 않았으면 null")
		LocalDateTime deletedAt,
		@Schema(description = "작성·수정·삭제·답변 이력. 남긴 순서대로")
		List<History> histories
	) {

		public static Detail of(Inquiry inquiry, List<InquiryHistory> histories) {
			return Detail.builder()
				.inquiryId(inquiry.getId())
				.authorType(inquiry.getAuthorType())
				.authorId(inquiry.getAuthorId())
				.businessId(inquiry.getBusinessId())
				.authorName(inquiry.getAuthorName())
				.authorEmail(inquiry.getAuthorEmail())
				.category(inquiry.getCategory())
				.categoryName(inquiry.getCategory().getLabel())
				.title(inquiry.getTitle())
				.content(inquiry.getContent())
				.imageUrls(inquiry.getImageUrls())
				.status(inquiry.getStatus())
				.booking(inquiry.getBooking() == null ? null : BookingInfo.from(inquiry.getBooking()))
				.answer(inquiry.getAnswer())
				.answeredBy(inquiry.getAnsweredBy())
				.answeredAt(inquiry.getAnsweredAt())
				.createdAt(inquiry.getCreatedAt())
				.updatedAt(inquiry.getUpdatedAt())
				.deletedAt(inquiry.getDeletedAt())
				.histories(histories.stream().map(History::from).toList())
				.build();
		}
	}

	/** 문의에 연결한 예매. 공연 제목은 지금 값이다. */
	@Builder
	@Schema(name = "AdminInquiryBooking", description = "문의에 연결한 예매")
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

	/** 이력 한 줄. 그 순간의 문의 내용 그대로라 답변 전에 고친 내용과 지운 문의의 내용을 여기서 확인한다. */
	@Builder
	@Schema(name = "AdminInquiryHistory", description = "문의 이력")
	public record History(
		InquiryAction action,
		@Schema(description = "행위자 종류 (USER / SELLER / ADMIN)")
		AccountType actorType,
		Long actorId,
		String title,
		String content,
		List<String> imageUrls,
		@Schema(description = "그 순간의 답변. 답변 전이면 null")
		String answer,
		LocalDateTime createdAt
	) {

		static History from(InquiryHistory history) {
			return History.builder()
				.action(history.getAction())
				.actorType(history.getActorType())
				.actorId(history.getActorId())
				.title(history.getTitle())
				.content(history.getContent())
				.imageUrls(history.getImageUrls())
				.answer(history.getAnswer())
				.createdAt(history.getCreatedAt())
				.build();
		}
	}
}
