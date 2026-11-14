package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 공연 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum PerformanceExceptionCode implements ExceptionCode {

	/** 없는 공연이거나 다른 사업체의 공연. 번호가 존재하는지 알려주지 않으려고 둘 다 같은 404로 답한다 */
	PERFORMANCE_NOT_FOUND("PERFORMANCE000", HttpStatus.NOT_FOUND, "존재하지 않는 공연입니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
