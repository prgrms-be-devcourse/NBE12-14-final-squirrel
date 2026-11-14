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

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 담당자 토큰으로 보내는 목록 요청. 대표 전용 기능이 아니라서 담당자로도 되는지 같이 확인한다. */
	private MockHttpServletRequestBuilder listRequest() {
		return get(URL).header("Authorization", token(AccountType.SELLER, "STAFF", MY_BUSINESS));
	}

	private void insertPolicy(long businessId, String name) {
		jdbc.update("INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, ?, NOW())", businessId, name);
	}

	private List<String> names(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].name");
	}

	private List<String> namesOf(MockHttpServletRequestBuilder request) throws Exception {
		return names(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
	}

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanList() throws Exception {
		mockMvc.perform(get(URL).header("Authorization", token(AccountType.USER, null, null)))
			.andExpect(status().isForbidden());
		mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
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
		assertThat(first.keySet()).containsExactlyInAnyOrder("cancelPolicyId", "name", "isDefault");
		assertThat(first.get("isDefault")).isEqualTo(true);
		assertThat(JsonPath.<Boolean>read(body, "$.data.content[1].isDefault")).isFalse();
		assertThat(JsonPath.<Integer>read(body, "$.data.totalElements")).isEqualTo(3);
	}

	@Test
	@DisplayName("내 규정이 없으면 기본 규정만 나오고, 정렬(sort) 파라미터는 무시한다")
	void onlyDefaultWhenNoOwnPolicyAndSortIgnored() throws Exception {
		insertPolicy(OTHER_BUSINESS, "남의 규정");

		assertThat(namesOf(listRequest())).containsExactly(DEFAULT_POLICY);

		insertPolicy(MY_BUSINESS, "가 규정");
		insertPolicy(MY_BUSINESS, "하 규정");
		assertThat(namesOf(listRequest().param("sort", "name,asc")))
			.containsExactly(DEFAULT_POLICY, "하 규정", "가 규정");
	}

	@Test
	@DisplayName("크기를 주지 않으면 20개씩 나오고, 기본 규정은 첫 페이지 맨 앞에만 나오며 전체 개수에 포함된다")
	void defaultPolicyOnlyOnFirstPage() throws Exception {
		for (int i = 0; i < 20; i++) {
			insertPolicy(MY_BUSINESS, "규정" + String.format("%02d", i));
		}

		MvcResult firstPage = mockMvc.perform(listRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(21))
			.andExpect(jsonPath("$.data.totalPages").value(2))
			.andReturn();
		assertThat(names(firstPage).getFirst()).isEqualTo(DEFAULT_POLICY);

		// 기본 규정이 첫 페이지 한 칸을 차지하므로 가장 먼저 만든 내 규정 하나가 두 번째 페이지로 넘어간다.
		assertThat(namesOf(listRequest().param("page", "1"))).containsExactly("규정00");
		assertThat(namesOf(listRequest().param("page", "2"))).isEmpty();
	}

	@Test
	@DisplayName("크기는 최대 20개로 줄어들고, 크기 0은 에러(500)가 아니라 기본값으로 처리된다")
	void pageSizeIsCappedAtTwenty() throws Exception {
		for (int i = 0; i < 20; i++) {
			insertPolicy(MY_BUSINESS, "규정" + i);
		}

		mockMvc.perform(listRequest().param("size", "21"))
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.size").value(20));
		mockMvc.perform(listRequest().param("size", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(20));
	}
}
