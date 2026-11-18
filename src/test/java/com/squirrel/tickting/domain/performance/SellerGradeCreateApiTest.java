package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 등급 추가(SLR-09).
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호로 공연을 넣고,
 * 끝나면 그 사업체의 공연과 등급·회차만 지운다.
 * 판매 좌석을 만드는 기능(SLR-14)이 아직 없어서 회차의 seats_generated_at 은 SQL 로 넣는다.
 */
class SellerGradeCreateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances/{performanceId}/grades";
	private static final long MY_BUSINESS = 897L;
	private static final long OTHER_BUSINESS = 898L;

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM seat_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
	}

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 대표 토큰으로 보내는 등급 추가 요청 */
	private ResultActions add(Long performanceId, Map<String, Object> body) throws Exception {
		return add(performanceId, body, token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private ResultActions add(Long performanceId, Map<String, Object> body, String authorization) throws Exception {
		return mockMvc.perform(post(URL, performanceId)
			.header("Authorization", authorization)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	/** null 값도 본문에 넣으려고 Map.of 대신 LinkedHashMap 을 쓴다. */
	private Map<String, Object> body(String name, Integer price) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("price", price);
		return body;
	}

	private Long insertPerformance(long businessId, String status) {
		Long venueId = jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '등급 추가 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?,
			        NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, status);
	}

	private void insertGrade(Long performanceId, String name) {
		jdbc.update("INSERT INTO seat_grade (performance_id, name, price) VALUES (?, ?, 100000)", performanceId, name);
	}

	private void insertSchedule(Long performanceId, String status, boolean seatsGenerated) {
		jdbc.update("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at,
			                                  updated_at)
			VALUES (?, NOW() + INTERVAL '30 days', ?, ?, NOW(), NOW())
			""", performanceId, status, seatsGenerated ? Timestamp.valueOf("2026-11-01 00:00:00") : null);
	}

	private List<Map<String, Object>> savedGrades(Long performanceId) {
		return jdbc.queryForList("SELECT id, name, price FROM seat_grade WHERE performance_id = ? ORDER BY id",
			performanceId);
	}

	@Test
	@DisplayName("등급을 추가하면 201 이고 등급 코드를 그대로 저장해 명세의 모양으로 돌려준다")
	void created() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");

		String response = add(performanceId, body("R", 154000))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();

		List<Map<String, Object>> saved = savedGrades(performanceId);
		assertThat(saved).hasSize(1);
		assertThat(saved.getFirst()).containsEntry("name", "R").containsEntry("price", 154000);
		assertThat(JsonPath.<Map<String, Object>>read(response, "$.data")).containsExactlyInAnyOrderEntriesOf(Map.of(
			"gradeId", ((Number) saved.getFirst().get("id")).intValue(),
			"name", "R", "price", 154000));
	}

	@Test
	@DisplayName("가격은 0원과 10,000,000원까지 받는다. 담당자도 추가할 수 있다")
	void boundariesAndStaff() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		String staff = token(AccountType.SELLER, "STAFF", MY_BUSINESS);

		add(performanceId, body("C", 0), staff).andExpect(status().isCreated());
		add(performanceId, body("VIP", 10_000_000)).andExpect(status().isCreated());

		assertThat(savedGrades(performanceId)).extracting(row -> row.get("price")).containsExactly(0, 10_000_000);
	}

	@Test
	@DisplayName("전석(ALL)도 다른 등급이 있는 공연에 만들어 둘 수 있다 (구역에 지정될 때 막는다)")
	void allCanBeCreatedWithOtherGrades() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		insertGrade(performanceId, "R");

		add(performanceId, body("ALL", 50000)).andExpect(status().isCreated());

		assertThat(savedGrades(performanceId)).extracting(row -> row.get("name")).containsExactly("R", "ALL");
	}

	@Test
	@DisplayName("입력 형식이 틀리면 400 COMMON000 이고 저장하지 않는다 (등급 없음·목록에 없는 등급, 가격 없음·음수·상한 초과)")
	void invalidInput() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		List<Map<String, Object>> bodies = List.of(
			body(null, 1000),
			body("", 1000),
			body("R석", 1000),
			body("VVIP", 1000),
			body("R", null),
			body("R", -1),
			body("R", 10_000_001));

		for (Map<String, Object> body : bodies) {
			add(performanceId, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON000"));
		}
		assertThat(savedGrades(performanceId)).isEmpty();
	}

	@Test
	@DisplayName("같은 공연에 같은 등급이 있으면 409 PERFORMANCE302 다")
	void duplicatedName() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		insertGrade(performanceId, "R");

		add(performanceId, body("R", 1000))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE302"));
		assertThat(savedGrades(performanceId)).hasSize(1);
	}

	@Test
	@DisplayName("다른 공연에 같은 등급이 있어도 추가할 수 있다")
	void sameNameInOtherPerformance() throws Exception {
		Long other = insertPerformance(MY_BUSINESS, "DRAFT");
		insertGrade(other, "R");
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");

		add(performanceId, body("R", 1000)).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("취소된 공연은 409 PERFORMANCE002 이다")
	void canceledPerformance() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "CANCELED");

		add(performanceId, body("R", 1000))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE002"));
		assertThat(savedGrades(performanceId)).isEmpty();
	}

	@Test
	@DisplayName("취소되지 않은 회차 중 하나라도 판매 좌석을 만들었으면 409 PERFORMANCE301 이고, 취소된 회차의 좌석만 있으면 추가할 수 있다")
	void locked() throws Exception {
		Long generated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(generated, "ON_SALE", false);
		insertSchedule(generated, "POSTPONED", true);
		Long onlyCanceledGenerated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(onlyCanceledGenerated, "CANCELED", true);
		insertSchedule(onlyCanceledGenerated, "ON_SALE", false);

		add(generated, body("R", 1000))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE301"));
		add(onlyCanceledGenerated, body("R", 1000)).andExpect(status().isCreated());
		assertThat(savedGrades(generated)).isEmpty();
	}

	@Test
	@DisplayName("다른 사업체의 공연은 취소·locked 상태와 상관없이 없는 공연과 같은 404 PERFORMANCE000 이다")
	void othersAndMissingAreNotFound() throws Exception {
		Long othersCanceled = insertPerformance(OTHER_BUSINESS, "CANCELED");
		Long othersLocked = insertPerformance(OTHER_BUSINESS, "PUBLISHED");
		insertSchedule(othersLocked, "ON_SALE", true);

		for (Long performanceId : List.of(othersCanceled, othersLocked, Long.MAX_VALUE)) {
			add(performanceId, body("R", 1000))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		}
		assertThat(savedGrades(othersCanceled)).isEmpty();
		assertThat(savedGrades(othersLocked)).isEmpty();
	}

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanCall() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");

		add(performanceId, body("R", 1000), token(AccountType.USER, null, null))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(URL, performanceId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body("R", 1000))))
			.andExpect(status().isUnauthorized());
		assertThat(savedGrades(performanceId)).isEmpty();
	}
}
