package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 공연장 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum VenueExceptionCode implements ExceptionCode {

	/** 공연을 등록하려는 공연장이 없음 */
	VENUE_NOT_FOUND("VENUE000", HttpStatus.NOT_FOUND, "존재하지 않는 공연장입니다."),
	/** 사용 중지(INACTIVE)된 공연장에는 새 공연을 등록할 수 없다. 이미 잡힌 공연은 그대로 둔다 */
	VENUE_INACTIVE("VENUE001", HttpStatus.BAD_REQUEST, "사용이 중지된 공연장입니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
