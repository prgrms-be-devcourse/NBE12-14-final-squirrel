package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 판매자의 내 공연 목록·상세(SLR-05·06).
 * 시드 공연(사업체 1번)을 믿는 다른 테스트가 있어서 공연 테이블을 비우지 않는다. 이 테스트는 시드에 없는
 * 사업체 번호(MY_BUSINESS, OTHER_BUSINESS)로 공연을 넣고, 끝나면 그 사업체의 데이터만 지운다.
 * 계정 종류·역할별 경로 규칙은 SecurityConfigTest 가 확인하므로 여기서는 이 API 가 막혀 있는지만 본다.
 */
class SellerPerformanceApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances";
	private static final long MY_BUSINESS = 777L;
	private static final long OTHER_BUSINESS = 778L;
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 10, 0);

	private final List<Long> insertedVenueIds = new ArrayList<>();

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM performance_image WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
		for (Long id : insertedVenueIds) {
			jdbc.update("DELETE FROM venue WHERE id = ?", id);
		}
		insertedVenueIds.clear();
	}

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 대표 토큰으로 보내는 목록 요청 */
	private MockHttpServletRequestBuilder listRequest() {
		return get(URL).header("Authorization", token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private Long arenaVenueId() {
		return jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
	}

	private Long defaultCancelPolicyId() {
		return jdbc.queryForObject("SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1",
			Long.class);
	}

	private Long insertPerformance(long businessId, Long venueId, String title, String status, LocalDateTime createdAt) {
		Timestamp time = Timestamp.valueOf(createdAt);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, cast_info, poster_url, notice, guide, status, sale_open_at,
			                         sale_close_at, max_per_person, receive_type, booking_fee, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?, 'CONCERT', 12, 150, '가상 밴드', 'https://cdn.example.com/poster.jpg', '공지', '안내',
			        ?, '2026-11-25 20:00:00', '2026-12-20 18:00:00', 4, 'BOTH', 2000, ?, ?)
			RETURNING id
			""", Long.class, businessId, venueId, defaultCancelPolicyId(), title, status, time, time);
	}

	private Long insertPerformance(long businessId, String title, String status, LocalDateTime createdAt) {
		return insertPerformance(businessId, arenaVenueId(), title, status, createdAt);
	}

	private void insertSchedule(Long performanceId, LocalDateTime startAt, String status) {
		insertSchedule(performanceId, startAt, status, null);
	}

	private void insertSchedule(Long performanceId, LocalDateTime startAt, String status,
		LocalDateTime seatsGeneratedAt) {
		jdbc.update("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at,
			                                  updated_at)
			VALUES (?, ?, ?, ?, NOW(), NOW())
			""", performanceId, Timestamp.valueOf(startAt), status,
			seatsGeneratedAt == null ? null : Timestamp.valueOf(seatsGeneratedAt));
	}

	/** 내 사업체 대표 토큰으로 보내는 상세 요청 */
	private MockHttpServletRequestBuilder detailRequest(Long performanceId) {
		return get(URL + "/" + performanceId).header("Authorization", token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private List<String> titles(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].title");
	}

	@Test
	@DisplayName("목록에는 내 사업체 공연만 나오고, 공연장명과 회차 수(취소 회차 포함, 회차 없으면 0)가 함께 나온다")
	void listShowsOnlyMyPerformances() throws Exception {
		Long withSchedules = insertPerformance(MY_BUSINESS, "회차 있는 공연", "PUBLISHED", BASE.plusDays(1));
		insertSchedule(withSchedules, BASE.plusDays(30), "ON_SALE");
		insertSchedule(withSchedules, BASE.plusDays(31), "PAUSED");
		insertSchedule(withSchedules, BASE.plusDays(32), "CANCELED");
		insertPerformance(MY_BUSINESS, "회차 없는 공연", "DRAFT", BASE);
		insertPerformance(OTHER_BUSINESS, "남의 공연", "PUBLISHED", BASE.plusDays(2));

		MvcResult result = mockMvc.perform(listRequest()).andExpect(status().isOk()).andReturn();
		String body = result.getResponse().getContentAsString();
		Map<String, Object> first = JsonPath.read(body, "$.data.content[0]");

		assertThat(titles(result)).containsExactly("회차 있는 공연", "회차 없는 공연");
		assertThat(first.keySet()).containsExactlyInAnyOrder("performanceId", "title", "status", "posterUrl",
			"venueName", "scheduleCount", "createdAt");
		assertThat(first.get("venueName")).isEqualTo("가상 아레나 (샘플)");
		assertThat(first.get("scheduleCount")).isEqualTo(3);
		assertThat(JsonPath.<Integer>read(body, "$.data.content[1].scheduleCount")).isZero();
	}

	// ---------------------------------------------------------------- 상세

	@Test
	@DisplayName("다른 사업체의 공연과 없는 공연은 똑같이 404 (PERFORMANCE000)이다")
	void othersAndMissingPerformanceAreNotFound() throws Exception {
		Long othersId = insertPerformance(OTHER_BUSINESS, "남의 공연", "PUBLISHED", BASE);

		mockMvc.perform(detailRequest(othersId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		mockMvc.perform(detailRequest(othersId + 1000))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
	}
}
