package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 약관 동의 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum TermsExceptionCode implements ExceptionCode {

	/** 필수 약관에 동의하지 않음 */
	REQUIRED_TERMS_NOT_AGREED("TERMS000", HttpStatus.BAD_REQUEST, "필수 약관에 모두 동의해 주세요."),
	/** 존재하지 않는 약관 번호 */
	TERMS_NOT_FOUND("TERMS001", HttpStatus.BAD_REQUEST, "존재하지 않는 약관입니다."),
	/** 개정되어 최신이 아닌 약관에 동의하려 함 */
	TERMS_OUTDATED("TERMS002", HttpStatus.BAD_REQUEST, "개정된 약관입니다. 최신 약관에 동의해 주세요.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
