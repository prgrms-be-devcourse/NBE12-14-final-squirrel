package com.squirrel.tickting.global.exception;

import com.squirrel.tickting.global.exception.code.SellerExceptionCode;
import com.squirrel.tickting.global.exception.code.UserExceptionCode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;

/**
 * DB 의 UNIQUE 인덱스 이름을 에러 코드로 연결한다.
 *
 * 서비스가 중복을 미리 확인해도 동시 요청은 그 확인을 함께 통과할 수 있고, 그 마지막 방어선이 DB 의 UNIQUE 인덱스다.
 * 이때 던져진 DataIntegrityViolationException 을 GlobalExceptionHandler 가 이 표로 "이메일 중복" 같은
 * 구체적인 에러로 바꿔 준다. 서비스마다 실패 후 다시 조회하거나 try/catch 할 필요가 없다.
 *
 * 새 UNIQUE 인덱스를 만들고 사용자에게 구체적으로 알려야 하면 여기에 한 줄 추가한다.
 * (인덱스 이름은 db/migration 의 이름과 같아야 한다. 틀리면 일반 충돌 에러 COMMON003 으로 나간다)
 */
public final class UniqueConstraintCodes {

	private static final Map<String, ExceptionCode> CODES = Map.of(
		"uq_user_email", UserExceptionCode.EMAIL_DUPLICATED,
		"uq_user_ci", UserExceptionCode.ALREADY_REGISTERED,
		"uq_seller_email", SellerExceptionCode.EMAIL_DUPLICATED,
		"uq_seller_business_number", SellerExceptionCode.BUSINESS_NUMBER_DUPLICATED
	);

	/** PostgreSQL 메시지: duplicate key value violates unique constraint "인덱스이름" */
	private static final Pattern MESSAGE_PATTERN = Pattern.compile("unique constraint \"([^\"]+)\"");

	private UniqueConstraintCodes() {
	}

	/** 예외(와 그 원인들)에서 위반한 제약 이름을 찾아, 등록된 에러 코드가 있으면 돌려준다. */
	public static Optional<ExceptionCode> find(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			String name = constraintName(cause);
			if (name != null && CODES.containsKey(name.toLowerCase(Locale.ROOT))) {
				return Optional.of(CODES.get(name.toLowerCase(Locale.ROOT)));
			}
			if (cause.getCause() == cause) {
				break;
			}
		}
		return Optional.empty();
	}

	private static String constraintName(Throwable cause) {
		if (cause instanceof ConstraintViolationException hibernate && hibernate.getConstraintName() != null) {
			return hibernate.getConstraintName();
		}
		if (cause.getMessage() != null) {
			Matcher matcher = MESSAGE_PATTERN.matcher(cause.getMessage());
			if (matcher.find()) {
				return matcher.group(1);
			}
		}
		return null;
	}
}
