package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** Flyway 로 만든 계정 테이블의 제약(부분 UNIQUE 인덱스 등)이 설계 문서대로 동작하는지 DB 에 직접 넣어 확인한다. */
class AccountSchemaTest extends IntegrationTestSupport {

	private void insertUser(String email, String ciHash, boolean deleted) {
		jdbc.update("""
			INSERT INTO user_member (email, password, name, nickname, phone, ci_hash, status, created_at, updated_at, deleted_at)
			VALUES (?, 'pw', '이름', '닉', '01012345678', ?, 'ACTIVE', now(), now(), ?)
			""", email, ciHash, deleted ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
	}

	private void insertSeller(long businessId, String email, String ciHash, String role, boolean deleted) {
		jdbc.update("""
			INSERT INTO seller_member (business_id, email, password, name, phone, role, ci_hash, status, created_at, updated_at, deleted_at)
			VALUES (?, ?, 'pw', '이름', '01012345678', ?, ?, 'ACTIVE', now(), now(), ?)
			""", businessId, email, role, ciHash, deleted ? java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()) : null);
	}

	@Test
	@DisplayName("예매자 이메일·CI 는 탈퇴하지 않은 회원끼리만 중복이 막힌다")
	void userUniqueOnlyAmongActive() {
		insertUser("a@example.com", "ci-1", false);

		assertThatThrownBy(() -> insertUser("a@example.com", "ci-2", false))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertUser("b@example.com", "ci-1", false))
			.isInstanceOf(DataIntegrityViolationException.class);

		// 탈퇴한 행은 중복 검사에서 빠지고, 탈퇴 회원끼리는 같은 값이 여러 개 있어도 된다
		insertUser("c@example.com", "ci-3", true);
		insertUser("c@example.com", "ci-3", true);
		insertUser("c@example.com", "ci-3", false);
		assertThat(count("user_member")).isEqualTo(4);
	}

	@Test
	@DisplayName("탈퇴 처리로 ci_hash 를 비운 행은 여러 개 있어도 된다 (NULL 은 중복이 아니다)")
	void nullCiHashAllowed() {
		jdbc.update("""
			INSERT INTO user_member (email, password, name, nickname, phone, status, created_at, updated_at)
			VALUES ('x@example.com', 'pw', '탈퇴회원', '탈퇴회원', '-', 'ACTIVE', now(), now()),
			       ('y@example.com', 'pw', '탈퇴회원', '탈퇴회원', '-', 'ACTIVE', now(), now())
			""");
		assertThat(count("user_member")).isEqualTo(2);
	}

	@Test
	@DisplayName("사업체당 대표(OWNER)는 1명이고 담당자(STAFF)는 여러 명이다")
	void oneOwnerPerBusiness() {
		insertSeller(1L, "owner@x.com", "ci-1", "OWNER", false);

		assertThatThrownBy(() -> insertSeller(1L, "owner2@x.com", "ci-2", "OWNER", false))
			.isInstanceOf(DataIntegrityViolationException.class);

		insertSeller(1L, "staff1@x.com", "ci-3", "STAFF", false);
		insertSeller(1L, "staff2@x.com", "ci-4", "STAFF", false);
		// 다른 사업체에는 또 대표가 있을 수 있다
		insertSeller(2L, "owner3@x.com", "ci-5", "OWNER", false);
		assertThat(count("seller_member")).isEqualTo(4);
	}

	@Test
	@DisplayName("같은 사람(CI)은 한 사업체에 두 번 들어올 수 없지만 다른 사업체는 막지 않는다")
	void ciUniquePerBusiness() {
		insertSeller(1L, "a@x.com", "ci-same", "OWNER", false);

		assertThatThrownBy(() -> insertSeller(1L, "b@x.com", "ci-same", "STAFF", false))
			.isInstanceOf(DataIntegrityViolationException.class);

		insertSeller(2L, "c@x.com", "ci-same", "OWNER", false);
		assertThat(count("seller_member")).isEqualTo(2);
	}

	@Test
	@DisplayName("판매자 이메일은 전역으로 유일하고, 비활성화(삭제)된 계정의 이메일은 다시 쓸 수 있다")
	void sellerEmailGloballyUnique() {
		insertSeller(1L, "a@x.com", "ci-1", "OWNER", false);

		assertThatThrownBy(() -> insertSeller(2L, "a@x.com", "ci-2", "OWNER", false))
			.isInstanceOf(DataIntegrityViolationException.class);

		insertSeller(1L, "gone@x.com", "ci-3", "STAFF", true);
		insertSeller(2L, "gone@x.com", "ci-4", "OWNER", false);
		assertThat(count("seller_member")).isEqualTo(3);
	}

	@Test
	@DisplayName("사업자등록번호, 약관 버전, 약관 동의(회원·약관 쌍)는 중복될 수 없다")
	void otherUniqueConstraints() {
		jdbc.update("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone,
			    contact_email, terms_agreed_at, status, created_at, updated_at)
			VALUES ('회사', '대표', '1111111111', '01011112222', 'c@x.com', now(), 'PENDING', now(), now())
			""");
		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone,
			    contact_email, terms_agreed_at, status, created_at, updated_at)
			VALUES ('회사2', '대표2', '1111111111', '01011112222', 'd@x.com', now(), 'PENDING', now(), now())
			""")).isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> saveTerms(com.squirrel.tickting.domain.terms.type.TermsType.SERVICE, "1.0", true))
			.isInstanceOf(DataIntegrityViolationException.class);

		jdbc.update("INSERT INTO user_terms_agreement (user_id, terms_id, agreed_at) VALUES (1, 1, now())");
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO user_terms_agreement (user_id, terms_id, agreed_at) VALUES (1, 1, now())"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
