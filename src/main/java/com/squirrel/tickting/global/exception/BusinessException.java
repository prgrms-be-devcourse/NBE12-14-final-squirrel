package com.squirrel.tickting.global.exception;

import lombok.Getter;

/**
 * 비즈니스 규칙 위반 시 던지는 예외.
 * ExceptionCode만 넘기면 핸들러가 HTTP 상태/응답 코드/메시지를 맞춰 ApiResponse로 내려준다.
 */
@Getter
public class BusinessException extends RuntimeException {

	private final ExceptionCode exceptionCode;

	public BusinessException(ExceptionCode exceptionCode) {
		super(exceptionCode.getMessage());
		this.exceptionCode = exceptionCode;
	}

	public BusinessException(ExceptionCode exceptionCode, String message) {
		super(message);
		this.exceptionCode = exceptionCode;
	}
}
