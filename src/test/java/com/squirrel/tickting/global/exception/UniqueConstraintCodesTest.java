package com.squirrel.tickting.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.global.exception.code.SellerExceptionCode;
import com.squirrel.tickting.global.exception.code.UserExceptionCode;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class UniqueConstraintCodesTest {

	private DataIntegrityViolationException violation(String constraintName) {
		var cause = new ConstraintViolationException("제약 위반", new SQLException("x"), "sql", constraintName);
		return new DataIntegrityViolationException("저장 실패", cause);
	}

	@Test
	@DisplayName("등록된 UNIQUE 인덱스 이름이면 대응하는 에러 코드를 찾는다 (대소문자 무시)")
	void findRegisteredConstraint() {
		assertThat(UniqueConstraintCodes.find(violation("uq_user_email")))
			.contains(UserExceptionCode.EMAIL_DUPLICATED);
		assertThat(UniqueConstraintCodes.find(violation("UQ_USER_CI")))
			.contains(UserExceptionCode.ALREADY_REGISTERED);
		assertThat(UniqueConstraintCodes.find(violation("uq_seller_email")))
			.contains(SellerExceptionCode.EMAIL_DUPLICATED);
		assertThat(UniqueConstraintCodes.find(violation("uq_seller_business_number")))
			.contains(SellerExceptionCode.BUSINESS_NUMBER_DUPLICATED);
	}

	@Test
	@DisplayName("제약 이름이 Hibernate 예외에 없어도 PostgreSQL 메시지에서 이름을 읽는다")
	void findFromMessage() {
		var e = new DataIntegrityViolationException("저장 실패", new SQLException(
			"ERROR: duplicate key value violates unique constraint \"uq_user_email\"\n  Detail: Key (email)=(a@b.com) already exists."));

		assertThat(UniqueConstraintCodes.find(e)).contains(UserExceptionCode.EMAIL_DUPLICATED);
	}

	@Test
	@DisplayName("등록되지 않은 제약이거나 제약 정보가 없으면 비어 있다 (일반 충돌 에러로 나간다)")
	void unknownConstraint() {
		assertThat(UniqueConstraintCodes.find(violation("uq_something_else"))).isEmpty();
		assertThat(UniqueConstraintCodes.find(new DataIntegrityViolationException("원인 없음"))).isEmpty();
		assertThat(UniqueConstraintCodes.find(violation(null))).isEmpty();
	}
}
