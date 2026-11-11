package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 도메인에 묶이지 않는 공통 에러 코드.
 * 요청 검증 실패, 처리하지 못한 서버 오류에 사용한다.
 *
 * 코드 형식은 "도메인 접두사 + 3자리 숫자"로 통일한다. (예: COMMON000, AUTH001)
 */
@Getter
@RequiredArgsConstructor
public enum CommonExceptionCode implements ExceptionCode {

	/** @Valid 실패 등 요청 값/형식이 잘못된 경우 (400) */
	INVALID_INPUT("COMMON000", HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	/** 경로는 맞지만 허용되지 않는 HTTP 메서드로 요청한 경우 (405) */
	METHOD_NOT_ALLOWED("COMMON001", HttpStatus.METHOD_NOT_ALLOWED, "이 경로에서 지원하지 않는 요청 방식입니다."),
	/** 존재하지 않는 경로로 요청한 경우 (404) */
	ENDPOINT_NOT_FOUND("COMMON002", HttpStatus.NOT_FOUND, "존재하지 않는 경로입니다."),
	/** 예상하지 못한 서버 예외. 원문 메시지는 클라이언트에 노출하지 않는다 (500) */
	INTERNAL_ERROR("COMMON999", HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
