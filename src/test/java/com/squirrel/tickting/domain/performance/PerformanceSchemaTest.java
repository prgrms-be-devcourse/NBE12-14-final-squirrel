package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 돈·환불 수수료에 쓰이는 값의 DB 제약(V13)이 서비스를 거치지 않는 쓰기도 막는지 DB 에 직접 넣어 확인한다.
 * 시드 공연·기본 규정을 믿는 다른 테스트가 있어서, 시드에 없는 사업체 번호로 넣은 행만 지운다.
 */
class PerformanceSchemaTest extends IntegrationTestSupport {

	private static final long MY_BUSINESS = 886L;

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id = " + MY_BUSINESS;
		jdbc.update("DELETE FROM seat_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id = ?", MY_BUSINESS);
		jdbc.update("""
			DELETE FROM cancel_policy_rule
			WHERE policy_id IN (SELECT id FROM cancel_policy WHERE business_id = ?)
			""", MY_BUSINESS);
		jdbc.update("DELETE FROM cancel_policy WHERE business_id = ?", MY_BUSINESS);
	}

	/** 판매 정책 칸만 바꿔 넣는 공연. 작성 중(DRAFT)이라 판매 정책은 비워 둘 수 있다. */
	private Long insertPerformance(String saleOpen, String saleClose, Integer maxPerPerson, Integer bookingFee) {
		Long venueId = jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, sale_open_at, sale_close_at, max_per_person,
			                         booking_fee, created_at, updated_at)
			VALUES (?, ?, 1, ?, '제약 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', 'DRAFT',
			        CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP), ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, MY_BUSINESS, venueId, policyId, saleOpen, saleClose, maxPerPerson, bookingFee);
	}

	private void insertRule(long policyId, int daysFrom, Integer daysTo, int feeRate) {
		jdbc.update("""
			INSERT INTO cancel_policy_rule (policy_id, days_from, days_to, fee_rate, cancelable)
			VALUES (?, ?, ?, ?, TRUE)
			""", policyId, daysFrom, daysTo, feeRate);
	}

	@Test
	@DisplayName("판매 종료가 오픈보다 늦지 않거나, 수수료가 음수거나, 1인 매수가 0이면 공연이 저장되지 않는다 (비어 있는 작성 중 공연은 통과)")
	void performanceSalePolicyChecks() {
		insertPerformance(null, null, null, null);

		assertThatThrownBy(() -> insertPerformance("2026-12-01 10:00:00", "2026-12-01 10:00:00", 4, 0))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_performance_sale_period");
		assertThatThrownBy(() -> insertPerformance(null, null, null, -1))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_performance_booking_fee");
		assertThatThrownBy(() -> insertPerformance(null, null, 0, null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_performance_max_per_person");
	}

	@Test
	@DisplayName("음수 등급 가격은 저장되지 않는다")
	void negativeGradePriceRejected() {
		Long performanceId = insertPerformance(null, null, null, null);

		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO seat_grade (performance_id, name, price) VALUES (?, 'R', -1)", performanceId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_seat_grade_price");
	}

	@Test
	@DisplayName("취소 규정 구간은 같은 시작 일수가 두 번 저장되지 않고, 수수료율 0~100·일수 범위를 벗어나면 저장되지 않는다")
	void cancelPolicyRuleChecks() {
		long policyId = jdbc.queryForObject(
			"INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, '제약 규정', NOW()) RETURNING id",
			Long.class, MY_BUSINESS);
		insertRule(policyId, 0, null, 0);

		assertThatThrownBy(() -> insertRule(policyId, 0, 3, 10))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uq_cancel_policy_rule_days_from");
		assertThatThrownBy(() -> insertRule(policyId, 1, null, 101))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_cancel_policy_rule_fee_rate");
		assertThatThrownBy(() -> insertRule(policyId, 5, 3, 10))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_cancel_policy_rule_days");
	}
}
