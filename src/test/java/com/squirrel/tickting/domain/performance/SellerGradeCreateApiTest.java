package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
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

	private Cookie token(AccountType type, String role, Long businessId) {
		return sessionCookie(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 대표 토큰으로 보내는 등급 추가 요청 */
	private ResultActions add(Long performanceId, Map<String, Object> body) throws Exception {
		return add(performanceId, body, token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private ResultActions add(Long performanceId, Map<String, Object> body, Cookie authorization) throws Exception {
		return mockMvc.perform(post(URL, performanceId)
			.cookie(authorization)
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
	@DisplayName("같은 등급을 동시에 추가하면 하나만 201 이고 나머지는 409 PERFORMANCE302 이며 저장은 1건이다 (UNIQUE 가 마지막 방어선)")
	void concurrentSameGradeAddsOnlyOne() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		int threads = 5;

		List<MvcResult> results = runConcurrently(threads, i -> () -> add(performanceId, body("R", 1000 + i)).andReturn());

		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 201).hasSize(1);
		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 409)
			.hasSize(threads - 1)
			.allSatisfy(result -> assertThat(result.getResponse().getContentAsString()).contains("PERFORMANCE302"));
		assertThat(savedGrades(performanceId)).hasSize(1);
	}
}
