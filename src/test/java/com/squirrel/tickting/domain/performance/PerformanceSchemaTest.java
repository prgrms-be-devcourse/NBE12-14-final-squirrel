package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 취소 규정 구간의 DB UNIQUE(V13)가 서비스를 거치지 않는 쓰기도 막는지 DB 에 직접 넣어 확인한다.
 * 시드 규정을 믿는 다른 테스트가 있어서, 시드에 없는 사업체 번호로 넣은 행만 지운다.
 */
class PerformanceSchemaTest extends IntegrationTestSupport {

	private static final long MY_BUSINESS = 886L;

	@AfterEach
	void deleteInsertedRows() {
		jdbc.update("""
			DELETE FROM cancel_policy_rule
			WHERE policy_id IN (SELECT id FROM cancel_policy WHERE business_id = ?)
			""", MY_BUSINESS);
		jdbc.update("DELETE FROM cancel_policy WHERE business_id = ?", MY_BUSINESS);
	}

	private void insertRule(long policyId, int daysFrom, Integer daysTo, int feeRate) {
		jdbc.update("""
			INSERT INTO cancel_policy_rule (policy_id, days_from, days_to, fee_rate, cancelable)
			VALUES (?, ?, ?, ?, TRUE)
			""", policyId, daysFrom, daysTo, feeRate);
	}

	@Test
	@DisplayName("취소 규정 구간은 같은 시작 일수가 두 번 저장되지 않는다")
	void cancelPolicyRuleDaysFromUnique() {
		long policyId = jdbc.queryForObject(
			"INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, '제약 규정', NOW()) RETURNING id",
			Long.class, MY_BUSINESS);
		insertRule(policyId, 0, null, 0);

		assertThatThrownBy(() -> insertRule(policyId, 0, 3, 10))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uq_cancel_policy_rule_days_from");
	}
}
