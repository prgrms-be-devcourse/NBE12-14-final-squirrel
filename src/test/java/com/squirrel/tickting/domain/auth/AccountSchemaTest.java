package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Flyway 로 만든 계정 테이블의 제약(부분 UNIQUE 인덱스 등)이 설계 문서대로 동작하는지 DB 에 직접 넣어 확인한다. */
class AccountSchemaTest extends IntegrationTestSupport {

	private void insertUser(String email, boolean deleted) {
		jdbc.update("""
			INSERT INTO user_member (email, password, name, nickname, phone, status, created_at, updated_at, deleted_at)
			VALUES (?, 'pw', '이름', '닉', '01012345678', 'ACTIVE', now(), now(), ?)
			""", email, deleted ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
	}

	private void insertSeller(long businessId, String email, String role, boolean deleted) {
		jdbc.update("""
			INSERT INTO seller_member (business_id, email, password, name, phone, role, status, created_at, updated_at, deleted_at)
			VALUES (?, ?, 'pw', '이름', '01012345678', ?, 'ACTIVE', now(), now(), ?)
			""", businessId, email, role, deleted ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
	}

	@Test
	@DisplayName("예매자 이메일은 탈퇴하지 않은 회원끼리만 중복이 막힌다")
	void userUniqueOnlyAmongActive() {
		insertUser("a@example.com", false);

		assertThatThrownBy(() -> insertUser("a@example.com", false))
			.isInstanceOf(DataIntegrityViolationException.class);

		// 탈퇴한 행은 중복 검사에서 빠지고, 탈퇴 회원끼리는 같은 값이 여러 개 있어도 된다
		insertUser("c@example.com", true);
		insertUser("c@example.com", true);
		insertUser("c@example.com", false);
		assertThat(count("user_member")).isEqualTo(4);
	}

	@Test
	@DisplayName("판매자 이메일은 전역으로 유일하고, 비활성화(삭제)된 계정의 이메일은 다시 쓸 수 있다")
	void sellerEmailGloballyUnique() {
		insertSeller(1L, "a@x.com", "OWNER", false);

		assertThatThrownBy(() -> insertSeller(2L, "a@x.com", "OWNER", false))
			.isInstanceOf(DataIntegrityViolationException.class);

		insertSeller(1L, "gone@x.com", "STAFF", true);
		insertSeller(2L, "gone@x.com", "OWNER", false);
		assertThat(count("seller_member")).isEqualTo(3);
	}
}
