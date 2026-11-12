package com.squirrel.tickting.domain.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.domain.terms.type.TermsType;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** 약관 조회. 테스트마다 IntegrationTestSupport 가 서비스 이용·개인정보(필수), 탈퇴(선택) 약관 1.0 을 넣어 둔다. */
class TermsApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/terms";

	private static List<String> types(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data[*].type");
	}

	@Test
	@DisplayName("로그인 없이 종류별 최신 약관을 서비스 이용 → 개인정보 → 탈퇴 순으로 조회한다")
	void listLatestWithoutLogin() throws Exception {
		mockMvc.perform(get(URL))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.code").value("0000"))
			.andExpect(jsonPath("$.data.length()").value(3))
			.andExpect(jsonPath("$.data[0].termId").value(requiredTermIds.get(0)))
			.andExpect(jsonPath("$.data[0].type").value("SERVICE"))
			.andExpect(jsonPath("$.data[0].version").value("1.0"))
			.andExpect(jsonPath("$.data[0].content").value("SERVICE 1.0"))
			.andExpect(jsonPath("$.data[0].required").value(true))
			.andExpect(jsonPath("$.data[1].type").value("PRIVACY"))
			.andExpect(jsonPath("$.data[2].termId").value(optionalTermId))
			.andExpect(jsonPath("$.data[2].type").value("WITHDRAW"))
			.andExpect(jsonPath("$.data[2].required").value(false));
	}

	@Test
	@DisplayName("약관이 개정되면 옛 버전은 빠지고 새 버전만 내려주며, 나중에 추가돼도 순서는 종류 순이다")
	void onlyLatestVersion() throws Exception {
		Long revised = saveTerms(TermsType.SERVICE, "2.0", true);

		MvcResult result = mockMvc.perform(get(URL))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(3))
			.andExpect(jsonPath("$.data[0].termId").value(revised))
			.andExpect(jsonPath("$.data[0].version").value("2.0"))
			.andReturn();

		assertThat(types(result)).containsExactly("SERVICE", "PRIVACY", "WITHDRAW");
	}

	@Test
	@DisplayName("type 을 주면 그 종류의 최신 약관만 조회한다")
	void filterByType() throws Exception {
		saveTerms(TermsType.PRIVACY, "1.1", true);

		mockMvc.perform(get(URL).param("type", "PRIVACY"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].type").value("PRIVACY"))
			.andExpect(jsonPath("$.data[0].version").value("1.1"));
	}

	@Test
	@DisplayName("요청한 종류의 약관이 없으면 에러가 아니라 빈 목록이다")
	void emptyWhenTypeHasNoTerms() throws Exception {
		jdbc.update("DELETE FROM terms WHERE type = 'WITHDRAW'");

		mockMvc.perform(get(URL).param("type", "WITHDRAW"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	@DisplayName("알 수 없는 type 이면 400 COMMON000 이다")
	void unknownType() throws Exception {
		mockMvc.perform(get(URL).param("type", "MARKETING"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}
}
