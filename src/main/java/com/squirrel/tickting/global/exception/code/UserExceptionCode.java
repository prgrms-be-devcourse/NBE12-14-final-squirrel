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
	/** 토큰의 회원 번호로 회원을 찾지 못함. 탈퇴한 뒤 액세스 토큰이 만료되기 전(최대 15분)에 호출하면 생긴다 */
	USER_NOT_FOUND("USER002", HttpStatus.NOT_FOUND, "회원 정보를 찾을 수 없습니다."),
	/** 결제 대기 중이거나 아직 시작하지 않은 공연의 결제 완료 예매가 있어서 탈퇴할 수 없음. 취소하거나 공연이 시작된 뒤에 탈퇴한다 */
	ONGOING_BOOKING_EXISTS("USER003", HttpStatus.CONFLICT,
		"진행 중인 예매가 있어 탈퇴할 수 없습니다. 예매를 취소하거나 공연이 시작된 뒤에, 취소된 공연은 환불이 끝난 뒤에 탈퇴해 주세요.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
