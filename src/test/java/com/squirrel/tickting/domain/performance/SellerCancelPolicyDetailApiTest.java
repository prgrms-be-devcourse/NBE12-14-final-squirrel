package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
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

	private long defaultPolicyId() {
		return jdbc.queryForObject("SELECT id FROM cancel_policy WHERE business_id IS NULL AND name = '기본 규정'",
			Long.class);
	}

	@Test
	@DisplayName("내 규정은 규정 정보와 구간을 주고, 구간은 저장 순서와 상관없이 daysFrom 큰 순이다")
	void myPolicyWithRulesSortedByDaysFromDesc() throws Exception {
		long policyId = insertPolicy(MY_BUSINESS, "공연장 자체 규정");
		insertRule(policyId, 0, 0, 0, false);
		insertRule(policyId, 7, null, 0, true);
		insertRule(policyId, 1, 6, 10, true);

		String body = requestDetail(policyId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.cancelPolicyId").value(policyId))
			.andExpect(jsonPath("$.data.name").value("공연장 자체 규정"))
			.andExpect(jsonPath("$.data.isDefault").value(false))
			.andExpect(jsonPath("$.data.editable").value(true))
			.andReturn().getResponse().getContentAsString();

		Map<String, Object> data = JsonPath.read(body, "$.data");
		assertThat(data.keySet()).containsExactlyInAnyOrder("cancelPolicyId", "name", "isDefault", "editable", "rules");
		List<Integer> daysFrom = JsonPath.read(body, "$.data.rules[*].daysFrom");
		assertThat(daysFrom).containsExactly(7, 1, 0);

		Map<String, Object> first = JsonPath.read(body, "$.data.rules[0]");
		assertThat(first).containsEntry("daysTo", null).containsEntry("feeRate", 0).containsEntry("cancelable", true);
		Map<String, Object> last = JsonPath.read(body, "$.data.rules[2]");
		assertThat(last).containsEntry("daysTo", 0).containsEntry("cancelable", false);
	}

	@Test
	@DisplayName("서비스 기본 규정도 볼 수 있고 isDefault 는 true 다")
	void defaultPolicyIsVisible() throws Exception {
		String body = requestDetail(defaultPolicyId())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("기본 규정"))
			.andExpect(jsonPath("$.data.isDefault").value(true))
			.andExpect(jsonPath("$.data.editable").value(false))
			.andReturn().getResponse().getContentAsString();

		List<Integer> daysFrom = JsonPath.read(body, "$.data.rules[*].daysFrom");
		assertThat(daysFrom).containsExactly(7, 3, 1, 0);
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

	@Test
	@DisplayName("규정 번호가 숫자가 아니면 400 COMMON000 이다")
	void nonNumericIdIsBadRequest() throws Exception {
		requestDetail("abc")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanView() throws Exception {
		long policyId = insertPolicy(MY_BUSINESS, "내 규정");

		mockMvc.perform(get(URL, policyId).header("Authorization", token(AccountType.USER, null, null)))
			.andExpect(status().isForbidden());
		mockMvc.perform(get(URL, policyId)).andExpect(status().isUnauthorized());
	}
}
