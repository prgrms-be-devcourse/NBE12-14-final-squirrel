package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 예매자 계정 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum UserExceptionCode implements ExceptionCode {

	/** 이미 가입된 이메일 (탈퇴한 회원의 이메일은 다시 쓸 수 있다) */
	EMAIL_DUPLICATED("USER000", HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
	/** 같은 본인인증 정보(CI)로 이미 가입한 사람. 1인 1계정 */
	ALREADY_REGISTERED("USER001", HttpStatus.CONFLICT, "이미 가입된 사용자입니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
