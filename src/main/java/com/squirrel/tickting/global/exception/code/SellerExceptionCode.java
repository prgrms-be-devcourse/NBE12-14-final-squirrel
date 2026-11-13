package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 판매자 계정·사업체 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum SellerExceptionCode implements ExceptionCode {

	/** 이미 가입된 판매자 이메일 */
	EMAIL_DUPLICATED("SELLER000", HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
	/** 이미 등록된 사업자등록번호 (같은 사업자의 중복 가입 방지) */
	BUSINESS_NUMBER_DUPLICATED("SELLER001", HttpStatus.CONFLICT, "이미 등록된 사업자등록번호입니다."),
	/** 본인인증으로 확인된 이름과 입력한 대표자명이 다름 */
	REPRESENTATIVE_NAME_MISMATCH("SELLER002", HttpStatus.BAD_REQUEST,
		"본인인증 이름과 대표자명이 일치하지 않습니다."),

	/** 관리자 승인 전 사업체 */
	BUSINESS_PENDING("SELLER003", HttpStatus.FORBIDDEN,
		"승인 대기 중인 사업체입니다. 관리자 승인 후 로그인할 수 있습니다."),
	/** 반려된 사업체 */
	BUSINESS_REJECTED("SELLER004", HttpStatus.FORBIDDEN,
		"가입 신청이 반려되었습니다. 사유를 확인하고 다시 신청해 주세요."),
	/** 이용이 정지된 사업체 */
	BUSINESS_SUSPENDED("SELLER005", HttpStatus.FORBIDDEN, "이용이 정지된 사업체입니다."),

	/** 관리자가 조회·처리하려는 사업체가 없음 */
	BUSINESS_NOT_FOUND("SELLER006", HttpStatus.NOT_FOUND, "존재하지 않는 사업체입니다."),
	/** 승인·반려는 승인 대기(PENDING) 사업체에만 할 수 있다. 이미 처리된 사업체에 다시 요청함 */
	BUSINESS_ALREADY_REVIEWED("SELLER007", HttpStatus.CONFLICT, "이미 처리된 사업체입니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
