package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 문의 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum InquiryExceptionCode implements ExceptionCode {

	/** 내 계정이 고를 수 없는 문의 유형. 예매자가 판매자 유형(정산 등)을 보낸 경우 */
	CATEGORY_NOT_ALLOWED("INQUIRY000", HttpStatus.BAD_REQUEST, "선택할 수 없는 문의 유형입니다."),
	/** 없는 문의, 남의 문의, 삭제한 문의. 번호가 존재하는지 알려주지 않으려고 같은 404로 답한다 */
	INQUIRY_NOT_FOUND("INQUIRY001", HttpStatus.NOT_FOUND, "존재하지 않는 문의입니다."),
	/** 답변이 끝난 문의를 수정·삭제하려는 경우. 수정·삭제와 답변이 동시에 들어와 답변이 먼저 끝난 경우도 같다 */
	INQUIRY_ALREADY_ANSWERED("INQUIRY002", HttpStatus.CONFLICT, "답변이 완료된 문의는 수정하거나 삭제할 수 없습니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
