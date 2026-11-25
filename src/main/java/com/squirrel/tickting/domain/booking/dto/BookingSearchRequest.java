package com.squirrel.tickting.domain.booking.dto;

import com.squirrel.tickting.domain.booking.type.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 예매 목록 검색 조건. 예매자·판매자·관리자가 같은 조건을 쓴다. 모두 비우면 최신 예매순 전체(역할별 기본 상태)다.
 * 누구의 예매인지(범위)는 여기 담지 않는다. 컨트롤러가 토큰으로 정한다.
 */
public record BookingSearchRequest(
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
