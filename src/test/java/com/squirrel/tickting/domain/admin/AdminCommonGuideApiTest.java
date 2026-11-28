package com.squirrel.tickting.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 관리자 공통 안내 목록·수정. 테스트 DB 에는 공통 안내 시드가 없어서(시연 시드는 로컬 전용) 운영처럼 빈 상태에서 시작한다. */
class AdminCommonGuideApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/admin/common-guides";

	@BeforeEach
	@AfterEach
	void cleanGuides() {
		jdbc.execute("TRUNCATE TABLE common_guide RESTART IDENTITY");
		jdbc.update("DELETE FROM audit_log WHERE target_type = 'COMMON_GUIDE'");
	}

	private Cookie admin(Long adminId) {
		return sessionCookie(new CustomUserDetails(adminId, AccountType.ADMIN, null, null));
	}

	private ResultActions update(Cookie session, String type, String content) throws Exception {
		return mockMvc.perform(put(URL + "/" + type)
			.cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("content", content))));
	}

	private List<Map<String, Object>> auditLogs() {
		return jdbc.queryForList("""
			SELECT actor_id, action, before_value::text AS before_value, after_value::text AS after_value
			FROM audit_log WHERE target_type = 'COMMON_GUIDE' ORDER BY id
			""");
	}

	@Test
	@DisplayName("없는 안내를 수정하면 새로 만들어지고, 다시 수정하면 내용이 바뀌며 감사 로그에 전후 내용이 남는다")
	void createsThenUpdatesWithAuditLog() throws Exception {
		mockMvc.perform(get(URL).cookie(admin(1L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].type").value("BOOKING_GUIDE"))
			.andExpect(jsonPath("$.data[0].content").doesNotExist());

		update(admin(1L), "BOOKING_GUIDE", " 7분 안에 결제해야 합니다. ")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").value("7분 안에 결제해야 합니다."))
			.andExpect(jsonPath("$.data.updatedBy").value(1))
			.andExpect(jsonPath("$.data.updatedAt").exists());
		update(admin(2L), "BOOKING_GUIDE", "10분 안에 결제해야 합니다.")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.updatedBy").value(2));

		mockMvc.perform(get(URL).cookie(admin(1L)))
			.andExpect(jsonPath("$.data[0].content").value("10분 안에 결제해야 합니다."))
			.andExpect(jsonPath("$.data[1].type").value("REFUND_GUIDE"))
			.andExpect(jsonPath("$.data[1].content").doesNotExist());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM common_guide", Integer.class)).isEqualTo(1);

		List<Map<String, Object>> logs = auditLogs();
		assertThat(logs).hasSize(2);
		assertThat(logs.get(0).get("before_value")).isNull();
		assertThat((String) logs.get(0).get("after_value")).contains("7분 안에 결제해야 합니다.");
		assertThat((String) logs.get(1).get("before_value")).contains("7분 안에 결제해야 합니다.");
		assertThat((String) logs.get(1).get("after_value")).contains("10분 안에 결제해야 합니다.");
		assertThat(logs.get(1).get("actor_id")).isEqualTo(2L);
	}

	@Test
	@DisplayName("여러 관리자가 동시에 고치면 차례로 바뀌어서, 감사 로그의 바뀌기 전 내용이 바로 앞 로그의 바뀐 뒤 내용과 이어진다")
	void concurrentUpdatesKeepAuditChain() throws Exception {
		update(admin(1L), "REFUND_GUIDE", "처음 안내").andExpect(status().isOk());

		List<Integer> statuses = runConcurrently(5, i -> () ->
			update(admin((long) i + 1), "REFUND_GUIDE", "안내 " + i).andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnly(200);
		List<Map<String, Object>> logs = auditLogs();
		assertThat(logs).hasSize(6);
		for (int i = 1; i < logs.size(); i++) {
			String previousAfter = (String) logs.get(i - 1).get("after_value");
			String before = (String) logs.get(i).get("before_value");
			// 잠금 없이 동시에 읽으면 둘 이상이 같은 "바뀌기 전" 값을 남겨서 이어지지 않는다
			assertThat(before).isEqualTo(previousAfter);
		}
		String lastAfter = (String) logs.get(logs.size() - 1).get("after_value");
		String saved = jdbc.queryForObject("SELECT content FROM common_guide WHERE type = 'REFUND_GUIDE'", String.class);
		assertThat(lastAfter).contains(saved);
	}
}
