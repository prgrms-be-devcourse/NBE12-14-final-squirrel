package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 판매자의 취소 규정 생성(SLR-21) 중 DB 의 UNIQUE 가 마지막 방어선이 되는 경우.
 * 서비스 기본 규정(V4 기준 데이터)은 지우지 않고, 시드에 없는 사업체 번호로 만든 규정·구간만 지운다.
 */
class SellerCancelPolicyCreateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/cancel-policies";
	private static final long MY_BUSINESS = 885L;

	@AfterEach
	void deleteInsertedRows() {
		jdbc.update("""
			DELETE FROM cancel_policy_rule
			WHERE policy_id IN (SELECT id FROM cancel_policy WHERE business_id = ?)
			""", MY_BUSINESS);
		jdbc.update("DELETE FROM cancel_policy WHERE business_id = ?", MY_BUSINESS);
	}

	private MvcResult create(String name) throws Exception {
		// daysTo 가 null 인 칸도 본문에 넣어야 해서 Map.of 대신 HashMap 을 쓴다.
		Map<String, Object> rule = new HashMap<>();
		rule.put("daysFrom", 0);
		rule.put("daysTo", null);
		rule.put("feeRate", 0);
		rule.put("cancelable", true);
		Map<String, Object> body = Map.of("name", name, "rules", List.of(rule));
		Cookie token = sessionCookie(
			new CustomUserDetails(1L, AccountType.SELLER, "STAFF", MY_BUSINESS));
		return mockMvc.perform(post(URL)
				.cookie(token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andReturn();
	}

	@Test
	@DisplayName("같은 사업체에서 대소문자만 다른 같은 이름을 동시에 만들면 하나만 201 이고 나머지는 409 PERFORMANCE104 이며 저장은 1건이다")
	void concurrentSameNameCreatesOnlyOne() throws Exception {
		List<String> names = List.of("동시 규정 A", "동시 규정 a", "동시 규정 A", "동시 규정 a", "동시 규정 A");

		List<MvcResult> results = runConcurrently(names.size(), i -> () -> create(names.get(i)));

		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 201).hasSize(1);
		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 409)
			.hasSize(names.size() - 1)
			.allSatisfy(result -> assertThat(result.getResponse().getContentAsString()).contains("PERFORMANCE104"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cancel_policy WHERE business_id = ?", Long.class,
			MY_BUSINESS)).isEqualTo(1L);
	}
}
