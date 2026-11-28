package com.squirrel.tickting.domain.booking.dto;

import com.squirrel.tickting.domain.booking.type.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

public class BookingRequest {

	private BookingRequest() {
	}

	/** 좌석 점유·예매 생성. 중복 번호는 서비스 흐름 앞에서 파사드가 거른다. */
	public record Create(
		@NotEmpty(message = "좌석을 한 개 이상 선택해 주세요.")
		List<@NotNull(message = "좌석 번호가 비어 있습니다.") Long> seatIds
	) {
	}

	/** 예매 취소. 예매 좌석 번호를 비우면(생략·빈 목록) 남은 좌석 전부를 취소한다. */
	public record Cancel(
		List<@NotNull(message = "예매 좌석 번호가 비어 있습니다.") Long> bookingSeatIds
	) {
	}

	private static final String TRACKING_NO_PATTERN = "[A-Za-z0-9-]{1,50}";
	private static final String TRACKING_NO_MESSAGE = "송장번호는 영문·숫자·하이픈 1~50자로 입력해 주세요.";

	/** 송장 단건 입력(SLR-41). 택배사 코드가 설정 목록에 있는지는 서비스가 확인한다. */
	public record Tracking(
		@Schema(description = "택배사 코드", example = "CJ")
		@NotBlank(message = "택배사를 선택해 주세요.")
		String carrier,

		@Schema(description = "송장번호", example = "123456789012")
		@NotNull(message = TRACKING_NO_MESSAGE)
		@Pattern(regexp = TRACKING_NO_PATTERN, message = TRACKING_NO_MESSAGE)
		String trackingNo
	) {
	}

	/** 송장 일괄 입력(SLR-42). 건수 상한(설정)·택배사 코드·예매 번호 중복은 서비스가 확인한다. */
	public record TrackingBulk(
		@NotEmpty(message = "송장을 한 건 이상 넣어 주세요.")
		List<@NotNull(message = "항목이 비어 있습니다.") @Valid TrackingRow> items
	) {
	}

	public record TrackingRow(
		@Schema(description = "예매 번호 (예매자에게 보이는 번호)", example = "20261125KCZB03R")
		@NotBlank(message = "예매 번호를 입력해 주세요.")
		String bookingNo,

		@Schema(description = "택배사 코드", example = "CJ")
		@NotBlank(message = "택배사를 선택해 주세요.")
		String carrier,

		@Schema(description = "송장번호", example = "123456789012")
		@NotNull(message = TRACKING_NO_MESSAGE)
		@Pattern(regexp = TRACKING_NO_PATTERN, message = TRACKING_NO_MESSAGE)
		String trackingNo
	) {
	}

	/**
	 * 예매 목록 검색 조건. 예매자·판매자·관리자가 같은 조건을 쓴다. 모두 비우면 최신 예매순 전체(역할별 기본 상태)다.
	 * 누구의 예매인지(범위)는 여기 담지 않는다. 컨트롤러가 토큰으로 정한다.
	 */
	public record Search(
		@Schema(description = "공연 제목. 일부만 넣어도 찾고 대소문자는 가리지 않는다", example = "콘서트")
		@Size(max = 100, message = "공연 제목은 100자 이하로 입력해 주세요.")
		String title,

		@Schema(description = "예매 상태. 여러 개를 고를 수 있다(status=PAID&status=CANCELED). 비우면 역할별 기본 상태")
		List<BookingStatus> status,

		@Schema(description = "예매한 날짜 시작(포함)", example = "2026-11-01")
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		LocalDate from,

		@Schema(description = "예매한 날짜 끝(포함)", example = "2026-11-30")
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
		LocalDate to
	) {
	}
}
