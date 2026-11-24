package com.squirrel.tickting.domain.performance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 취소 규정 상세(SLR-20).
 * 서비스 기본 규정은 마이그레이션(V4)이 넣는 기준 데이터라 지우지 않는다. 시드에 없는 사업체 번호로 규정을 넣고,
 * 끝나면 그 사업체의 규정과 구간만 지운다. (논리 FK 라 규정을 지워도 구간이 같이 지워지지 않는다)
 */
class SellerCancelPolicyDetailApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/cancel-policies/{cancelPolicyId}";
	private static final long MY_BUSINESS = 883L;
	private static final long OTHER_BUSINESS = 884L;

	@AfterEach
	void deleteInsertedRows() {
		jdbc.update("""
			DELETE FROM cancel_policy_rule
			WHERE policy_id IN (SELECT id FROM cancel_policy WHERE business_id IN (?, ?))
			""", MY_BUSINESS, OTHER_BUSINESS);
		jdbc.update("DELETE FROM cancel_policy WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
	}

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 담당자 토큰으로 보내는 요청. 대표 전용 기능이 아니라서 담당자로도 되는지 같이 확인한다. */
	private ResultActions requestDetail(Object cancelPolicyId) throws Exception {
		return mockMvc.perform(get(URL, cancelPolicyId)
			.header("Authorization", token(AccountType.SELLER, "STAFF", MY_BUSINESS)));
	}

	private long insertPolicy(long businessId, String name) {
		return jdbc.queryForObject(
			"INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, ?, NOW()) RETURNING id",
			Long.class, businessId, name);
	}

	private void insertRule(long policyId, int daysFrom, Integer daysTo, int feeRate, boolean cancelable) {
		jdbc.update("""
			INSERT INTO cancel_policy_rule (policy_id, days_from, days_to, fee_rate, cancelable)
			VALUES (?, ?, ?, ?, ?)
			""", policyId, daysFrom, daysTo, feeRate, cancelable);
	}

	@Test
	@DisplayName("다른 사업체의 규정과 없는 규정은 같은 404 PERFORMANCE100 이다")
	void otherBusinessPolicyIsNotFound() throws Exception {
		long othersPolicy = insertPolicy(OTHER_BUSINESS, "남의 규정");
		insertRule(othersPolicy, 0, null, 0, true);

		requestDetail(othersPolicy)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE100"));
		requestDetail(Long.MAX_VALUE)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE100"));
	}
}
