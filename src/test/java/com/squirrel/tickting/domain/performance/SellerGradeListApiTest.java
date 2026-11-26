package com.squirrel.tickting.domain.performance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 판매자의 등급·구역 조회(SLR-08).
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호로 공연을 넣고,
 * 끝나면 그 사업체의 공연과 등급·구역 설정·회차만 지운다. 공연장은 시드의 가상 아레나(22개 구역)를 쓴다.
 * 판매 좌석을 만드는 기능(SLR-14)이 아직 없어서 회차의 seats_generated_at 은 SQL 로 넣는다.
 */
class SellerGradeListApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances/{performanceId}/grades";
	private static final long MY_BUSINESS = 895L;
	private static final long OTHER_BUSINESS = 896L;

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM zone_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
	}

	private Cookie token(AccountType type, String role, Long businessId) {
		return sessionCookie(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 대표 토큰으로 보내는 조회 요청 */
	private MockHttpServletRequestBuilder request(Long performanceId) {
		return get(URL, performanceId).cookie(token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private Long arenaVenueId() {
		return jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
	}

	private Long insertPerformance(long businessId, String status) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '등급 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, NOW(),
			        NOW())
			RETURNING id
			""", Long.class, businessId, arenaVenueId(), policyId, status);
	}

	/**
	 * 공연 등록과 같이 공연장의 모든 구역을 UNSET 으로 만든다.
	 * 구역 순서와 거꾸로 넣어서, 응답이 저장 순서가 아니라 구역 순서(sortOrder)로 정렬되는지 확인할 수 있게 한다.
	 */
	private void initializeZones(Long performanceId) {
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, assignment_status)
			SELECT ?, z.id, 'UNSET'
			FROM venue_zone z
			WHERE z.venue_id = ?
			ORDER BY z.sort_order DESC, z.id DESC
			""", performanceId, arenaVenueId());
	}

	private boolean locked(Long performanceId) throws Exception {
		String body = mockMvc.perform(request(performanceId)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.locked");
	}

	@Test
	@DisplayName("다른 사업체의 공연과 없는 공연은 모두 404 PERFORMANCE000 이다")
	void othersAndMissingAreNotFound() throws Exception {
		Long others = insertPerformance(OTHER_BUSINESS, "PUBLISHED");
		initializeZones(others);

		mockMvc.perform(request(others))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		mockMvc.perform(request(Long.MAX_VALUE))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
	}
}
