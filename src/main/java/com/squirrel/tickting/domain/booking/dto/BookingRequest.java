package com.squirrel.tickting.domain.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public class BookingRequest {

	private BookingRequest() {
	}

	/** 좌석 점유·예매 생성. 중복 번호는 서비스 흐름 앞에서 파사드가 거른다. */
	public record Create(
		@NotEmpty(message = "좌석을 한 개 이상 선택해 주세요.")
		List<@NotNull(message = "좌석 번호가 비어 있습니다.") Long> seatIds
	) {
	}
}
