package com.squirrel.tickting.global.exception;

import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.exception.code.SellerExceptionCode;
import com.squirrel.tickting.global.exception.code.UserExceptionCode;
import java.util.Arrays;
import java.util.Locale;
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
 * 새 UNIQUE 인덱스를 만들고 사용자에게 구체적으로 알려야 하면 아래 Constraint 에 한 줄(인덱스 이름, 에러 코드) 추가한다.
 * (인덱스 이름은 db/migration 의 이름과 같아야 한다. 틀리면 일반 충돌 에러 COMMON003 으로 나간다)
 * uq_cancel_policy_rule_days_from 은 일부러 등록하지 않는다. 분산락이 뚫린 동시 수정에서만 걸려서 일반 충돌이면 충분하다.
 */
public final class UniqueConstraintCodes {

	/** 인덱스 이름과 에러 코드를 한 줄씩 짝지은 표. 인덱스 이름은 소문자로 비교한다. */
	private enum Constraint {

		USER_EMAIL("uq_user_email", UserExceptionCode.EMAIL_DUPLICATED),
		// 판매자 이메일: 활성 계정은 이메일당 하나, 같은 회사 안에서는 이메일당 하나 (V24)
		SELLER_EMAIL_ACTIVE("uq_seller_email_active", SellerExceptionCode.EMAIL_DUPLICATED),
		SELLER_BUSINESS_EMAIL("uq_seller_business_email", SellerExceptionCode.EMAIL_DUPLICATED),
		SELLER_BUSINESS_NUMBER("uq_seller_business_number", SellerExceptionCode.BUSINESS_NUMBER_DUPLICATED),
		SEAT_GRADE_NAME("uq_seat_grade_performance_name", PerformanceExceptionCode.GRADE_NAME_DUPLICATED),
		CANCEL_POLICY_NAME("uq_cancel_policy_business_name", PerformanceExceptionCode.CANCEL_POLICY_NAME_DUPLICATED),		// 락이 뚫려 같은 좌석이 두 번 예매되려 할 때 DB 가 마지막으로 막는다. 사용자에게는 "이미 선택된 좌석"으로 알린다.
		BOOKING_SEAT_ACTIVE("uq_booking_seat_active", BookingExceptionCode.SEAT_ALREADY_TAKEN);

		private final String indexName;
		private final ExceptionCode code;

		Constraint(String indexName, ExceptionCode code) {
			this.indexName = indexName;
			this.code = code;
		}

		static Optional<ExceptionCode> codeOf(String indexName) {
			return Arrays.stream(values())
				.filter(constraint -> constraint.indexName.equals(indexName))
				.map(constraint -> constraint.code)
				.findFirst();
		}
	}

	/** PostgreSQL 메시지: duplicate key value violates unique constraint "인덱스이름" */
	private static final Pattern MESSAGE_PATTERN = Pattern.compile("unique constraint \"([^\"]+)\"");

	private UniqueConstraintCodes() {
	}

	/** 예외(와 그 원인들)에서 위반한 제약 이름을 찾아, 등록된 에러 코드가 있으면 돌려준다. */
	public static Optional<ExceptionCode> find(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			String name = constraintName(cause);
			if (name != null) {
				Optional<ExceptionCode> code = Constraint.codeOf(name.toLowerCase(Locale.ROOT));
				if (code.isPresent()) {
					return code;
				}
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
