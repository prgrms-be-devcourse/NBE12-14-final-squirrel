package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 판매자의 취소 규정 목록(SLR-19).
 * 서비스 기본 규정은 마이그레이션(V4)이 넣는 기준 데이터라 지우지 않는다. 이 테스트는 시드에 없는
 * 사업체 번호(MY_BUSINESS, OTHER_BUSINESS)로 규정을 넣고, 끝나면 그 사업체의 규정만 지운다.
 * 계정 종류·역할별 경로 규칙은 SecurityConfigTest 가 확인하므로 여기서는 이 API 가 막혀 있는지만 본다.
 */
class SellerCancelPolicyListApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/cancel-policies";
	private static final String DEFAULT_POLICY = "기본 규정";
	private static final long MY_BUSINESS = 881L;
	private static final long OTHER_BUSINESS = 882L;

	@AfterEach
	void deleteInsertedRows() {
		jdbc.update("DELETE FROM cancel_policy WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
	}

	private Cookie token(AccountType type, String role, Long businessId) {
		return sessionCookie(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 담당자 토큰으로 보내는 목록 요청. 대표 전용 기능이 아니라서 담당자로도 되는지 같이 확인한다. */
	private MockHttpServletRequestBuilder listRequest() {
		return get(URL).cookie(token(AccountType.SELLER, "STAFF", MY_BUSINESS));
	}

	private void insertPolicy(long businessId, String name) {
		jdbc.update("INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, ?, NOW())", businessId, name);
	}

	private List<String> names(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].name");
	}

	@Test
	@DisplayName("기본 규정이 맨 앞에 나오고, 내 규정은 최신(번호 큰) 순이며, 다른 사업체의 규정은 나오지 않는다")
	void defaultFirstThenMineNewestFirst() throws Exception {
		insertPolicy(MY_BUSINESS, "먼저 만든 규정");
		insertPolicy(OTHER_BUSINESS, "남의 규정");
		insertPolicy(MY_BUSINESS, "나중에 만든 규정");

		MvcResult result = mockMvc.perform(listRequest()).andExpect(status().isOk()).andReturn();
		String body = result.getResponse().getContentAsString();
		Map<String, Object> first = JsonPath.read(body, "$.data.content[0]");

		assertThat(names(result)).containsExactly(DEFAULT_POLICY, "나중에 만든 규정", "먼저 만든 규정");
		assertThat(first.keySet()).containsExactlyInAnyOrder("cancelPolicyId", "name", "isDefault", "editable");
		assertThat(first.get("isDefault")).isEqualTo(true);
		assertThat(first.get("editable")).isEqualTo(false);
		assertThat(JsonPath.<Boolean>read(body, "$.data.content[1].isDefault")).isFalse();
		assertThat(JsonPath.<Integer>read(body, "$.data.totalElements")).isEqualTo(3);
	}
}
