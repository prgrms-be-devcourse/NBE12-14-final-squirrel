package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 인증·인가·토큰 관련 에러 코드.
 * 로그인 실패, 권한 부족, 로그인 필요에 사용한다.
 */
@Getter
@RequiredArgsConstructor
public enum AuthExceptionCode implements ExceptionCode {

	/** 인증 실패. 원인을 특정하지 못할 때 (시큐리티 AuthenticationException 등) */
	AUTHENTICATION_FAILED("AUTH000", HttpStatus.UNAUTHORIZED, "인증에 실패했습니다."),
	/** 이메일 없음 또는 비밀번호 불일치. 어느 쪽인지 노출하지 않는다 */
	INVALID_CREDENTIALS("AUTH001", HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
	/** 비활성화(정지)된 계정 */
	ACCOUNT_DISABLED("AUTH003", HttpStatus.UNAUTHORIZED, "비활성화된 계정입니다."),
	/** 같은 IP 에서 같은 이메일로 로그인에 여러 번 실패해 잠시 막힘 (app.auth.login.*). 잠긴 동안은 비밀번호가 맞아도 이 코드다 */
	LOGIN_TOO_MANY_ATTEMPTS("AUTH004", HttpStatus.TOO_MANY_REQUESTS, "로그인에 여러 번 실패했습니다. 잠시 후 다시 시도해 주세요."),
	/** 가입할 때 비밀번호와 비밀번호 확인이 다름 (예매자·판매자 가입 공통) */
	PASSWORD_CONFIRM_MISMATCH("AUTH005", HttpStatus.BAD_REQUEST, "비밀번호와 비밀번호 확인이 일치하지 않습니다."),

	// AUTH301 은 쓰지 않는다. (로그인 후 인증을 없애면서 비웠다. 번호를 다시 쓰지 않는다)
	/** 재발송 간격(60초) 안에 인증번호를 다시 요청함 (app.auth.email-verification.resend-interval) */
	EMAIL_VERIFICATION_TOO_SOON("AUTH302", HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 보낼 수 있습니다."),
	/** 하루 발송 한도 초과. 한국 시간 자정에 초기화된다 (app.auth.email-verification.daily-limit) */
	EMAIL_VERIFICATION_DAILY_LIMIT("AUTH303", HttpStatus.TOO_MANY_REQUESTS, "오늘은 더 보낼 수 없습니다. 내일 다시 시도해 주세요."),
	/** 발송 대기가 가득 차서 접수하지 못함. 오픈 직후처럼 요청이 몰릴 때만 난다. 재발송 간격·하루 횟수에 넣지 않는다 */
	EMAIL_SEND_QUEUE_FULL("AUTH304", HttpStatus.SERVICE_UNAVAILABLE, "요청이 많습니다. 잠시 후 다시 시도해 주세요."),
	/** 인증번호가 틀림 (기회가 남은 경우). 메시지에 남은 횟수를 넣어 던진다 */
	EMAIL_VERIFICATION_CODE_MISMATCH("AUTH305", HttpStatus.BAD_REQUEST, "인증번호가 올바르지 않습니다."),
	/** 유효한 인증번호가 없음: 만료, 틀린 횟수 초과로 폐기, 받은 적 없음. 사용자가 할 일은 "다시 받기" 하나라 한 코드로 묶었다 */
	EMAIL_VERIFICATION_CODE_INVALID("AUTH306", HttpStatus.BAD_REQUEST, "인증번호가 만료되었거나 없습니다. 다시 받아 주세요."),
	/** 이메일 인증을 마치지 않았거나 인증 유효 시간(20분)이 지난 채로 예매자·판매자 가입을 함. 화면은 이메일 인증을 다시 하게 한다 */
	EMAIL_NOT_VERIFIED("AUTH307", HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다. 인증이 만료됐다면 다시 인증해 주세요."),

	/** 비밀번호 재설정 메일을 요청한 이메일로 가입한 해당 종류(예매자·판매자)의 계정이 없음. 메시지에 계정 종류를 넣어 던진다. 삭제(탈퇴)된 계정도 없는 것으로 본다 */
	PASSWORD_RESET_EMAIL_NOT_FOUND("AUTH308", HttpStatus.NOT_FOUND, "가입되지 않은 이메일입니다."),
	/** 비밀번호 재설정 링크가 만료되었거나 이미 사용됨(다른 링크로 비밀번호를 바꿔 폐기된 경우, 링크를 받은 뒤 계정이 탈퇴한 경우 포함). 사용자가 할 일은 "다시 요청" 하나라 한 코드로 둔다 */
	PASSWORD_RESET_TOKEN_INVALID("AUTH309", HttpStatus.BAD_REQUEST, "만료되었거나 이미 사용한 링크입니다. 다시 요청해 주세요."),

	/** 로그인은 됐으나 권한 부족 (예: 예매자가 관리자 API 호출, 403) */
	ACCESS_DENIED("AUTH100", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

	/** 로그인하지 않았거나 세션이 만료됨 (SESSION 쿠키가 없거나 세션을 찾을 수 없음) */
	TOKEN_MISSING("AUTH200", HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
