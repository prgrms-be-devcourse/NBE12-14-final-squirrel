package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 캡챠(자동 입력 방지) 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum CaptchaExceptionCode implements ExceptionCode {

	/** 캡챠 토큰이 없거나, 만료·재사용됐거나, 점수가 낮거나, 다른 화면에서 받은 토큰이다. 대기열 줄 서기에서 쓴다 */
	CAPTCHA_FAILED("CAPTCHA000", HttpStatus.FORBIDDEN, "자동 입력 방지 확인에 실패했습니다. 새로고침 후 다시 시도해 주세요."),

	/** 캡챠 서버(구글)에 연결할 수 없거나 응답이 늦다 */
	CAPTCHA_UNAVAILABLE("CAPTCHA001", HttpStatus.SERVICE_UNAVAILABLE, "자동 입력 방지 확인을 할 수 없습니다. 잠시 후 다시 시도해 주세요.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
