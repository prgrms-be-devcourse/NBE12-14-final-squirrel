package com.squirrel.tickting.domain.performance.dto;

import com.squirrel.tickting.domain.performance.service.PerformanceSaveCommand;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 판매자의 공연 요청. 등록(SLR-02)과 수정(SLR-03)이 같은 본문을 쓴다. */
public class SellerPerformanceRequest {

	/**
	 * 포스터와 상세 이미지 주소는 http 또는 https 로 시작하고 공백이 없어야 한다.
	 * 화면이 그대로 이미지 주소로 쓰므로 javascript: 같은 값을 막는다. 내 정보 수정의 profileImg 와 같은 규칙이다.
	 */
	private static final String IMAGE_URL = "^https?://\\S+$";
	private static final String IMAGE_URL_MESSAGE = "이미지 주소는 http 또는 https 주소로 입력해 주세요.";

	private SellerPerformanceRequest() {
	}

	/**
	 * 공연 등록·수정 본문. 판매 필드 5개는 작성 중(DRAFT)에는 비워 둘 수 있고, 공개(SLR-04) 때 모두 채워졌는지 본다.
	 * 공백 제거와 "비면 null"은 검증보다 먼저 일어나야 해서 생성자에서 한다. (공백만 있는 공연명이 @NotBlank 에 걸리게)
	 */
	@Schema(name = "SellerPerformanceSaveRequest", description = "공연 등록·수정 요청")
	public record Save(

		@NotNull(message = "공연장을 선택해 주세요.")
		@Schema(description = "공연장 번호 (사용 중인 공연장만)", example = "1")
		Long venueId,

		@NotNull(message = "취소 규정을 선택해 주세요.")
		@Schema(description = "취소 규정 번호 (서비스 기본 규정 또는 내 사업체 규정)", example = "1")
		Long cancelPolicyId,

		@NotBlank(message = "공연명을 입력해 주세요.")
		@Size(max = 200, message = "공연명은 200자 이하로 입력해 주세요.")
		@Schema(description = "공연명", example = "가상 콘서트")
		String title,

		@NotNull(message = "장르를 선택해 주세요.")
		@Schema(description = "장르", example = "CONCERT")
		Genre genre,

		@NotNull(message = "관람 연령을 입력해 주세요.")
		@Min(value = 0, message = "관람 연령은 0세 이상이어야 합니다.")
		@Max(value = 100, message = "관람 연령은 100세 이하여야 합니다.")
		@Schema(description = "관람 연령 (만 N세 이상, 전체 관람가는 0)", example = "12")
		Integer ageLimit,

		@NotNull(message = "러닝타임을 입력해 주세요.")
		@Min(value = 1, message = "러닝타임은 1분 이상이어야 합니다.")
		@Max(value = 1440, message = "러닝타임은 1440분(24시간) 이하여야 합니다.")
		@Schema(description = "러닝타임(분)", example = "150")
		Integer runningTime,

		@Schema(description = "출연진. 비우면 없음", example = "가상 밴드")
		String castInfo,

		@NotBlank(message = "포스터 주소를 입력해 주세요.")
		@Size(max = 2048, message = "포스터 주소는 2048자 이하로 입력해 주세요.")
		@Pattern(regexp = IMAGE_URL, message = IMAGE_URL_MESSAGE)
		@Schema(description = "포스터 주소", example = "https://cdn.example.com/posters/12.jpg")
		String posterUrl,

		@Schema(description = "판매자 공지. 비우면 없음", example = "공연 시작 30분 전부터 입장 가능합니다.")
		String notice,

		@Schema(description = "이용 안내. 비우면 없음", example = "촬영·녹음은 금지됩니다.")
		String guide,

		@Schema(description = "판매 오픈 일시. 작성 중에는 비워 둘 수 있다")
		LocalDateTime saleOpenAt,

		@Schema(description = "판매 종료 일시. 작성 중에는 비워 둘 수 있고, 오픈과 둘 다 있으면 오픈보다 늦어야 한다")
		LocalDateTime saleCloseAt,

		// 상한 5장은 임시 값이다. (정책 확정 전)
		@Min(value = 1, message = "1인 최대 매수는 1장 이상이어야 합니다.")
		@Max(value = 5, message = "1인 최대 매수는 5장 이하여야 합니다.")
		@Schema(description = "1인 최대 매수 (1~5, 임시). 작성 중에는 비워 둘 수 있다", example = "4")
		Integer maxPerPerson,

		@Schema(description = "수령 방법. 작성 중에는 비워 둘 수 있다", example = "ONSITE")
		ReceiveType receiveType,

		// 상한 10,000원은 임시 값이다. (정책 확정 전)
		@Min(value = 0, message = "예매 수수료는 0원 이상이어야 합니다.")
		@Max(value = 10000, message = "예매 수수료는 10,000원 이하여야 합니다.")
		@Schema(description = "예매 수수료 (티켓 1장당 원, 0~10,000, 임시). 작성 중에는 비워 둘 수 있다", example = "2000")
		Integer bookingFee,

		// 장수 제한은 없다(임시). 순서값 중복은 목록 안의 관계라 서비스에서 본다.
		@Schema(description = "상세 이미지. 비우면 없음")
		List<@NotNull(message = "이미지 정보를 입력해 주세요.") @Valid Image> images
	) {

		public Save {
			title = trim(title);
			posterUrl = trim(posterUrl);
			castInfo = trimToNull(castInfo);
			notice = trimToNull(notice);
			guide = trimToNull(guide);
		}

		public PerformanceSaveCommand toCommand() {
			List<PerformanceSaveCommand.Image> imageCommands = images == null ? List.of() : images.stream()
				.map(image -> new PerformanceSaveCommand.Image(image.url(), image.sortOrder()))
				.toList();
			return PerformanceSaveCommand.builder()
				.title(title)
				.genre(genre)
				.ageLimit(ageLimit)
				.runningTime(runningTime)
				.castInfo(castInfo)
				.posterUrl(posterUrl)
				.notice(notice)
				.guide(guide)
				.saleOpenAt(saleOpenAt)
				.saleCloseAt(saleCloseAt)
				.maxPerPerson(maxPerPerson)
				.receiveType(receiveType)
				.bookingFee(bookingFee)
				.images(imageCommands)
				.build();
		}
	}

	@Schema(name = "SellerPerformanceImageRequest", description = "공연 상세 이미지")
	public record Image(

		@NotBlank(message = "이미지 주소를 입력해 주세요.")
		@Size(max = 2048, message = "이미지 주소는 2048자 이하로 입력해 주세요.")
		@Pattern(regexp = IMAGE_URL, message = IMAGE_URL_MESSAGE)
		@Schema(description = "이미지 주소", example = "https://cdn.example.com/details/12-1.jpg")
		String url,

		@NotNull(message = "이미지 순서를 입력해 주세요.")
		@Min(value = 0, message = "이미지 순서는 0 이상이어야 합니다.")
		@Schema(description = "보여줄 순서. 같은 요청 안에서 겹치면 안 된다", example = "1")
		Integer sortOrder
	) {

		public Image {
			url = trim(url);
		}
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}

	/** 선택 입력은 공백만 보낸 것을 "입력 안 함"으로 본다. 빈 문자열이 저장되면 화면이 "값 있음"으로 읽는다. */
	private static String trimToNull(String value) {
		String trimmed = trim(value);
		return trimmed == null || trimmed.isEmpty() ? null : trimmed;
	}
}
