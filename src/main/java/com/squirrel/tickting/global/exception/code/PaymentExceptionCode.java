package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 결제 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum PaymentExceptionCode implements ExceptionCode {

	/** 공연이 받지 않는 수령 방법. 현장 수령만 되는 공연에 배송을 고른 경우 등 */
	RECEIVE_TYPE_NOT_ALLOWED("PAYMENT000", HttpStatus.BAD_REQUEST, "이 공연에서 선택할 수 없는 수령 방법입니다."),
	/** 배송을 골랐는데 받는 사람 이름·연락처·주소 중 비어 있는 것이 있음 */
	RECIPIENT_REQUIRED("PAYMENT001", HttpStatus.BAD_REQUEST, "배송 받을 사람의 이름, 연락처, 주소를 모두 입력해 주세요.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
