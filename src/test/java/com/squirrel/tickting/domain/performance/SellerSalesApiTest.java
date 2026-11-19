package com.squirrel.tickting.domain.performance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 판매자의 판매 현황(SLR-17).
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호로 공연을 넣고,
 * 끝나면 그 사업체의 공연·회차·판매 좌석만 지운다. 공연장은 시드의 가상 아레나를 쓰고 판매 좌석은 그 좌석을 복사해 SQL 로 넣는다.
 */
class SellerSalesApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances/{performanceId}/sales";
	private static final long MY_BUSINESS = 897L;
	private static final long OTHER_BUSINESS = 898L;

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM schedule_seat WHERE schedule_id IN (SELECT id FROM performance_schedule"
			+ " WHERE performance_id IN (" + mine + "))");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
	}

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	private MockHttpServletRequestBuilder request(Long performanceId) {
		return get(URL, performanceId).header("Authorization", token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private Long arenaVenueId() {
		return jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
	}

	private Integer arenaTotalSeats() {
		return jdbc.queryForObject("SELECT total_seats FROM venue WHERE id = ?", Integer.class, arenaVenueId());
	}

	private Long insertPerformance(long businessId, String status) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '판매 현황 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, NOW(),
			        NOW())
			RETURNING id
			""", Long.class, businessId, arenaVenueId(), policyId, status);
	}

	private Long insertSchedule(Long performanceId, LocalDateTime startAt, String status, boolean generated) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at, updated_at)
			VALUES (?, ?, ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(startAt), status, generated ? Timestamp.valueOf(startAt.minusDays(1)) : null);
	}

	/** 공연장 좌석을 offset 번째부터 count 개 복사해 지정한 상태의 판매 좌석으로 넣는다. 서로 겹치지 않게 offset 을 달리 준다. */
	private void insertSeats(Long scheduleId, String status, int offset, int count, LocalDateTime heldUntil) {
		jdbc.update("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, status, held_until, version, updated_at)
			SELECT ?, vs.id, vs.zone_id, ?, ?, 0, NOW()
			FROM (SELECT id, zone_id FROM venue_seat WHERE venue_id = ? ORDER BY id OFFSET ? LIMIT ?) vs
			""", scheduleId, status, heldUntil == null ? null : Timestamp.valueOf(heldUntil), arenaVenueId(), offset, count);
	}

	@Test
	@DisplayName("좌석을 만든 회차는 상태별 개수(없는 상태는 0)를, 만들지 않은 회차는 null 을 주고 총 좌석은 공연장 좌석 수다")
	void countsByStatus() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		Long generated = insertSchedule(performanceId, LocalDateTime.of(2030, 1, 1, 19, 0), "ON_SALE", true);
		insertSchedule(performanceId, LocalDateTime.of(2030, 1, 2, 19, 0), "ON_SALE", false);
		insertSeats(generated, "SOLD", 0, 3, null);
		insertSeats(generated, "AVAILABLE", 3, 5, null);
		insertSeats(generated, "RESERVED", 8, 2, null);

		mockMvc.perform(request(performanceId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.schedules[0].seatsGenerated").value(true))
			.andExpect(jsonPath("$.data.schedules[0].totalSeats").value(arenaTotalSeats()))
			.andExpect(jsonPath("$.data.schedules[0].soldSeats").value(3))
			.andExpect(jsonPath("$.data.schedules[0].heldSeats").value(0))
			.andExpect(jsonPath("$.data.schedules[0].availableSeats").value(5))
			.andExpect(jsonPath("$.data.schedules[0].reservedSeats").value(2))
			.andExpect(jsonPath("$.data.schedules[0].blockedSeats").value(0))
			.andExpect(jsonPath("$.data.schedules[1].seatsGenerated").value(false))
			.andExpect(jsonPath("$.data.schedules[1].totalSeats").value(arenaTotalSeats()))
			.andExpect(jsonPath("$.data.schedules[1].soldSeats").doesNotExist())
			.andExpect(jsonPath("$.data.schedules[1].blockedSeats").doesNotExist());
	}

	@Test
	@DisplayName("취소된 회차도 공연 일시 빠른 순으로 나오고, 만료됐지만 정리 전인 임시 점유는 heldSeats 로 센다")
	void includesCanceledScheduleAndExpiredHold() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		// 늦은 회차를 먼저 넣어서 정렬이 저장 순서가 아니라 공연 일시순인지 본다.
		Long late = insertSchedule(performanceId, LocalDateTime.of(2030, 1, 3, 19, 0), "ON_SALE", true);
		Long canceled = insertSchedule(performanceId, LocalDateTime.of(2030, 1, 1, 19, 0), "CANCELED", true);
		insertSeats(late, "HELD", 0, 2, LocalDateTime.now().minusMinutes(1));
		insertSeats(canceled, "AVAILABLE", 0, 4, null);

		mockMvc.perform(request(performanceId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.schedules[0].scheduleId").value(canceled))
			.andExpect(jsonPath("$.data.schedules[0].status").value("CANCELED"))
			.andExpect(jsonPath("$.data.schedules[0].availableSeats").value(4))
			.andExpect(jsonPath("$.data.schedules[1].scheduleId").value(late))
			.andExpect(jsonPath("$.data.schedules[1].heldSeats").value(2));
	}

	@Test
	@DisplayName("작성 중·취소된 공연도 조회되고, 회차가 없으면 빈 목록이다")
	void draftAndCanceledPerformanceAreReadable() throws Exception {
		Long draft = insertPerformance(MY_BUSINESS, "DRAFT");
		Long canceled = insertPerformance(MY_BUSINESS, "CANCELED");

		mockMvc.perform(request(draft))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.schedules").isEmpty());
		mockMvc.perform(request(canceled))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.schedules").isEmpty());
	}

	@Test
	@DisplayName("다른 사업체의 공연과 없는 공연은 모두 404 PERFORMANCE000 이다")
	void othersAndMissingAreNotFound() throws Exception {
		Long others = insertPerformance(OTHER_BUSINESS, "PUBLISHED");

		mockMvc.perform(request(others))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		mockMvc.perform(request(Long.MAX_VALUE))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
	}

	@Test
	@DisplayName("담당자도 조회할 수 있고 예매자 토큰은 403 이다")
	void staffAllowedAndUserForbidden() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");

		mockMvc.perform(get(URL, performanceId).header("Authorization", token(AccountType.SELLER, "STAFF", MY_BUSINESS)))
			.andExpect(status().isOk());
		mockMvc.perform(get(URL, performanceId).header("Authorization", token(AccountType.USER, "USER", null)))
			.andExpect(status().isForbidden());
	}
}
