package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 취소 규정 생성(SLR-21).
 * 구간끼리의 규칙은 경우가 많아 서비스 테스트(CancelPolicyServiceTest)가 맡고, 여기서는 대표 경우 하나로 응답 모양만 본다.
 * 서비스 기본 규정(V4 기준 데이터)은 지우지 않고, 시드에 없는 사업체 번호로 만든 규정과 구간만 지운다.
 * 같은 이름의 동시 생성은 아직 막지 못해서(UNIQUE 는 DB 관련 TODO) 동시성 테스트는 그 인덱스를 만들 때 추가한다.
 */
class SellerCancelPolicyCreateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/cancel-policies";
	private static final long MY_BUSINESS = 885L;
	private static final long OTHER_BUSINESS = 886L;

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

	/** 대표 전용 기능이 아니라서 기본은 담당자 토큰으로 보낸다. */
	private ResultActions create(long businessId, Object body) throws Exception {
		return create(token(AccountType.SELLER, "STAFF", businessId), body);
	}

	private ResultActions create(String token, Object body) throws Exception {
		return mockMvc.perform(post(URL)
			.header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private static Map<String, Object> rule(int daysFrom, Integer daysTo, int feeRate, boolean cancelable) {
		// daysTo 가 null 인 칸도 본문에 넣어야 해서 Map.of 대신 HashMap 을 쓴다.
		Map<String, Object> rule = new HashMap<>();
		rule.put("daysFrom", daysFrom);
		rule.put("daysTo", daysTo);
		rule.put("feeRate", feeRate);
		rule.put("cancelable", cancelable);
		return rule;
	}

	private static Map<String, Object> body(String name, List<Map<String, Object>> rules) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("rules", rules);
		return body;
	}

	/** 구간이 맞는 가장 작은 본문(0일 전부터 끝까지 한 구간) */
	private static Map<String, Object> validBody(String name) {
		return body(name, List.of(rule(0, null, 0, true)));
	}

	private int countRules(long policyId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM cancel_policy_rule WHERE policy_id = ?", Integer.class, policyId);
	}

	private int countPolicies(long businessId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM cancel_policy WHERE business_id = ?", Integer.class, businessId);
	}

	@Test
	@DisplayName("규정과 구간을 토큰의 사업체로 저장하고, 응답은 상세와 같은 모양이며 구간은 입력 순서와 상관없이 daysFrom 큰 순이다")
	void createsPolicyWithRules() throws Exception {
		Map<String, Object> body = body("  공연장 자체 규정  ", List.of(
			rule(1, 2, 20, true), rule(0, 0, 0, false), rule(7, null, 0, true), rule(3, 6, 10, true)));

		String response = create(MY_BUSINESS, body)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.name").value("공연장 자체 규정"))
			.andExpect(jsonPath("$.data.isDefault").value(false))
			.andExpect(jsonPath("$.data.editable").value(true))
			.andReturn().getResponse().getContentAsString();

		Map<String, Object> data = JsonPath.read(response, "$.data");
		assertThat(data.keySet()).containsExactlyInAnyOrder("cancelPolicyId", "name", "isDefault", "editable", "rules");
		List<Integer> daysFrom = JsonPath.read(response, "$.data.rules[*].daysFrom");
		assertThat(daysFrom).containsExactly(7, 3, 1, 0);
		Map<String, Object> sameDay = JsonPath.read(response, "$.data.rules[3]");
		assertThat(sameDay).containsEntry("daysTo", 0).containsEntry("feeRate", 0).containsEntry("cancelable", false);

		long policyId = JsonPath.<Number>read(response, "$.data.cancelPolicyId").longValue();
		Map<String, Object> saved = jdbc.queryForMap("SELECT business_id, name FROM cancel_policy WHERE id = ?", policyId);
		assertThat(((Number) saved.get("business_id")).longValue()).isEqualTo(MY_BUSINESS);
		assertThat(saved.get("name")).isEqualTo("공연장 자체 규정");
		assertThat(countRules(policyId)).isEqualTo(4);
	}

	@Test
	@DisplayName("대표 토큰으로도 만들 수 있다")
	void ownerCanCreate() throws Exception {
		create(token(AccountType.SELLER, "OWNER", MY_BUSINESS), validBody("대표 규정"))
			.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("구간끼리의 규칙을 어기면 400 PERFORMANCE101 이고 무엇이 틀렸는지 메시지로 알려 주며 저장하지 않는다")
	void invalidRulesAreBadRequest() throws Exception {
		Map<String, Object> gap = body("빈 날짜 규정", List.of(rule(0, 2, 0, true), rule(4, null, 0, true)));

		create(MY_BUSINESS, gap)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PERFORMANCE101"))
			.andExpect(jsonPath("$.message").value("구간 사이에 빈 날짜나 겹치는 날짜가 있습니다."));
		assertThat(countPolicies(MY_BUSINESS)).isZero();
	}

	@Test
	@DisplayName("같은 사업체 안에서 대소문자·앞뒤 공백만 다른 이름이나 기본 규정 이름은 409 PERFORMANCE104 다")
	void duplicatedNameIsConflict() throws Exception {
		create(MY_BUSINESS, validBody("Hall Policy")).andExpect(status().isCreated());

		create(MY_BUSINESS, validBody(" hall policy "))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE104"));
		create(MY_BUSINESS, validBody("기본 규정"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE104"));
		assertThat(countPolicies(MY_BUSINESS)).isEqualTo(1);
	}

	@Test
	@DisplayName("다른 사업체와는 이름이 겹쳐도 만들 수 있다")
	void sameNameInOtherBusinessIsAllowed() throws Exception {
		create(OTHER_BUSINESS, validBody("공연장 자체 규정")).andExpect(status().isCreated());

		create(MY_BUSINESS, validBody("공연장 자체 규정")).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("입력 형식이 틀리면 400 COMMON000 이고 저장하지 않는다 (이름 누락·길이, 구간 개수, 범위, 빠진 값)")
	void invalidInputIsBadRequest() throws Exception {
		List<Map<String, Object>> elevenRules = new ArrayList<>();
		for (int day = 0; day < 10; day++) {
			elevenRules.add(rule(day, day, 0, true));
		}
		elevenRules.add(rule(10, null, 0, true));
		Map<String, Object> missingCancelable = rule(0, null, 0, true);
		missingCancelable.remove("cancelable");
		List<Map<String, Object>> nullRule = new ArrayList<>();
		nullRule.add(null);

		List<Object> bodies = List.of(
			validBody("   "),
			validBody("가".repeat(51)),
			body("규정", null),
			body("규정", List.of()),
			body("규정", elevenRules),
			body("규정", List.of(rule(0, 0, 0, true), rule(1, 91, 0, true), rule(92, null, 0, true))),
			body("규정", List.of(rule(0, null, 101, true))),
			body("규정", List.of(rule(-1, null, 0, true))),
			body("규정", List.of(missingCancelable)),
			body("규정", nullRule));
		for (Object body : bodies) {
			create(MY_BUSINESS, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON000"));
		}
		assertThat(countPolicies(MY_BUSINESS)).isZero();
	}

	@Test
	@DisplayName("이름 50자와 구간 10개, 일수 90은 경계값이라 만들 수 있다")
	void boundaryValuesAreAllowed() throws Exception {
		List<Map<String, Object>> tenRules = new ArrayList<>();
		for (int day = 0; day < 9; day++) {
			tenRules.add(rule(day, day, 0, true));
		}
		tenRules.add(rule(9, null, 100, true));

		create(MY_BUSINESS, body("가".repeat(50), tenRules)).andExpect(status().isCreated());
		create(MY_BUSINESS, body("90일 규정", List.of(rule(0, 89, 0, true), rule(90, null, 0, true))))
			.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanCreate() throws Exception {
		create(token(AccountType.USER, null, null), validBody("규정")).andExpect(status().isForbidden());
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validBody("규정"))))
			.andExpect(status().isUnauthorized());
	}
}
