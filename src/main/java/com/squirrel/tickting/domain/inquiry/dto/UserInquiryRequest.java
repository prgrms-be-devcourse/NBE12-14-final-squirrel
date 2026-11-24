package com.squirrel.tickting.domain.inquiry.dto;

import com.squirrel.tickting.domain.image.validation.ImageUrl;
import com.squirrel.tickting.domain.inquiry.type.InquiryCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 예매자 문의 요청. 문의 도메인을 판매자·관리자가 함께 쓰므로 역할 이름을 붙여 파일을 나눈다. */
public class UserInquiryRequest {

	private UserInquiryRequest() {
	}

	/** 문의 작성. 길이·사진 수는 임시 정책이고 컬럼 크기와 같다. (doc/inquiry-design.md) */
	public record Create(
		@NotNull(message = "문의 유형을 선택해 주세요.")
		@Schema(description = "문의 유형. 예매자 유형 목록에 있는 값만", example = "CANCEL_REFUND")
		InquiryCategory category,

		@Schema(description = "관련 예매 번호(내부 번호). 고르지 않으면 비운다. 내 예매만 고를 수 있다", example = "1001")
		Long bookingId,

		@NotBlank(message = "제목을 입력해 주세요.")
		@Size(max = 100, message = "제목은 100자 이하로 입력해 주세요.")
		@Schema(description = "제목", example = "환불이 아직 들어오지 않았어요")
		String title,

		@NotBlank(message = "내용을 입력해 주세요.")
		@Size(max = 2000, message = "내용은 2000자 이하로 입력해 주세요.")
		@Schema(description = "내용", example = "11/27 에 취소했는데 아직 환불되지 않았습니다.")
		String content,

		@Size(max = 3, message = "사진은 3장까지 첨부할 수 있습니다.")
		@Schema(description = "첨부 사진. 비우면 없음. 보낸 순서대로 저장한다")
		List<@Valid @NotNull(message = "사진이 비어 있습니다.") Image> images
	) {

		/** 사진 주소 목록. 비어 있으면 빈 목록이다. */
		public List<String> imageUrls() {
			return images == null ? List.of() : images.stream().map(Image::url).toList();
		}
	}

	/** 첨부 사진 하나. 공연 상세 이미지처럼 객체로 받는다. (@ImageUrl 은 목록의 문자열 원소에 걸 수 없다) */
	public record Image(
		@NotBlank(message = "사진 주소를 입력해 주세요.")
		@Size(max = 2048, message = "사진 주소는 2048자 이하로 입력해 주세요.")
		@ImageUrl
		@Schema(description = "이미지 업로드(IMG-01, purpose USER_INQUIRY)의 imageUrl 값",
			example = "https://cdn.example.com/public/user_inquiry/user-5/3f2a9c1e.jpg")
		String url
	) {
	}
}
