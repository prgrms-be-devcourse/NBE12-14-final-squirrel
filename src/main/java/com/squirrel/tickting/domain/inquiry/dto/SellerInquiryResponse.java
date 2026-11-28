package com.squirrel.tickting.domain.inquiry.dto;

import com.squirrel.tickting.domain.inquiry.infra.entity.Inquiry;
import com.squirrel.tickting.domain.inquiry.type.InquiryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/**
 * 판매자 문의 응답. 같은 사업체의 대표·담당자가 서로의 문의를 보므로 작성자를 함께 준다.
 * 유형·작성 결과는 예매자 문의와 모양이 같아 UserInquiryResponse 의 것을 쓴다.
 * 문의 행의 값만 써서(작성자는 작성 때 복사한 이름) 연관을 읽지 않는다.
 */
public class SellerInquiryResponse {

	private SellerInquiryResponse() {
	}

	@Builder
	@Schema(name = "SellerInquirySummary", description = "사업체 문의 목록 항목")
	public record Summary(
		Long inquiryId,
		UserInquiryResponse.Category category,
		String title,
		InquiryStatus status,
		@Schema(description = "작성한 판매자 계정 번호")
		Long authorId,
		@Schema(description = "작성 때의 작성자 이름", example = "김대표")
		String authorName,
		LocalDateTime createdAt,
		@Schema(description = "답변 일시. 답변 전이면 null")
		LocalDateTime answeredAt
	) {

		public static Summary from(Inquiry inquiry) {
			return Summary.builder()
				.inquiryId(inquiry.getId())
				.category(UserInquiryResponse.Category.from(inquiry.getCategory()))
				.title(inquiry.getTitle())
				.status(inquiry.getStatus())
				.authorId(inquiry.getAuthorId())
				.authorName(inquiry.getAuthorName())
				.createdAt(inquiry.getCreatedAt())
				.answeredAt(inquiry.getAnsweredAt())
				.build();
		}
	}

	@Builder
	@Schema(name = "SellerInquiryDetail", description = "사업체 문의 상세")
	public record Detail(
		Long inquiryId,
		UserInquiryResponse.Category category,
		String title,
		String content,
		@Schema(description = "첨부 사진 주소. 없으면 빈 배열")
		List<String> imageUrls,
		InquiryStatus status,
		@Schema(description = "작성한 판매자 계정 번호")
		Long authorId,
		@Schema(description = "작성 때의 작성자 이름", example = "김대표")
		String authorName,
		@Schema(description = "관리자 답변. 답변 전이면 null")
		String answer,
		LocalDateTime answeredAt,
		LocalDateTime createdAt,
		LocalDateTime updatedAt
	) {

		public static Detail from(Inquiry inquiry) {
			return Detail.builder()
				.inquiryId(inquiry.getId())
				.category(UserInquiryResponse.Category.from(inquiry.getCategory()))
				.title(inquiry.getTitle())
				.content(inquiry.getContent())
				.imageUrls(inquiry.getImageUrls())
				.status(inquiry.getStatus())
				.authorId(inquiry.getAuthorId())
				.authorName(inquiry.getAuthorName())
				.answer(inquiry.getAnswer())
				.answeredAt(inquiry.getAnsweredAt())
				.createdAt(inquiry.getCreatedAt())
				.updatedAt(inquiry.getUpdatedAt())
				.build();
		}
	}
}
