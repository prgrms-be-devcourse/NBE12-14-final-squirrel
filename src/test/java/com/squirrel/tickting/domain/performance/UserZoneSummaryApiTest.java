package com.squirrel.tickting.domain.performance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구역 요약(PFM-03)과 구역 좌석 상세(PFM-04). 공연장·구역·좌석·공연·등급·회차·판매 좌석을 SQL 로 직접 넣고 확인한다.
 * (공연장 생성·판매 좌석 생성 API 는 다른 파트 작업이라 기대지 않는다)
 * 테스트 트랜잭션 안에서 넣고 끝나면 되돌려서 시드 데이터는 그대로 남는다.
 */
@Transactional
class UserZoneSummaryApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/schedules/%d/zones";
	private static final String SEATS_URL = "/api/v1/schedules/%d/zones/%d/seats";

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private Long venueId;
	private Long cancelPolicyId;

	@BeforeEach
	void setUpData() {
		cancelPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		venueId = saveVenue("sample-arena");
	}

	// ---- 데이터 만들기 ----

	private Long saveVenue(String layoutKey) {
		return jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, layout_key, created_at, updated_at)
			VALUES ('가상 아레나', '서울 가상구 1', '서울', 100, 'ACTIVE', ?, ?, ?) RETURNING id
			""", Long.class, layoutKey, now, now);
	}

	private Long saveZone(String name, String floor, int sortOrder) {
		return jdbc.queryForObject("""
			INSERT INTO venue_zone (venue_id, name, floor, sort_order) VALUES (?, ?, ?, ?) RETURNING id
			""", Long.class, venueId, name, floor, sortOrder);
	}

	private Long savePerformance(String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, sale_open_at, sale_close_at, max_per_person,
			                         receive_type, booking_fee, created_at, updated_at)
			VALUES (1, ?, 1, ?, '가상 콘서트', 'CONCERT', 12, 150, 'https://cdn.example.com/p.png', ?, ?, ?, 4,
			        'ONSITE', 2000, ?, ?)
			RETURNING id
			""", Long.class, venueId, cancelPolicyId, status, now.minusDays(1), now.plusDays(30), now, now);
	}

	/** 판매 좌석을 이미 만든 회차. */
	private Long saveSchedule(Long performanceId, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?) RETURNING id
			""", Long.class, performanceId, now.plusDays(7), status, now, now, now);
	}

	// ---- 테스트 ----

	@Test
	@DisplayName("작성 중·취소된 공연의 회차, 취소된 회차, 없는 회차는 모두 404 PERFORMANCE200 이다")
	void invisibleSchedule() throws Exception {
		Long draft = saveSchedule(savePerformance("DRAFT"), "ON_SALE");
		Long canceledPerformance = saveSchedule(savePerformance("CANCELED"), "CANCELED");
		Long canceledSchedule = saveSchedule(savePerformance("PUBLISHED"), "CANCELED");
		Long missing = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1000 FROM performance_schedule", Long.class);

		for (Long scheduleId : List.of(draft, canceledPerformance, canceledSchedule, missing)) {
			mockMvc.perform(get(URL.formatted(scheduleId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE200"));
		}
	}

	// ---- 구역 좌석 상세 (PFM-04) ----

	@Test
	@DisplayName("작성 중 공연의 회차와 취소된 회차의 구역 좌석은 404 PERFORMANCE200 이다")
	void zoneSeatsOfInvisibleSchedule() throws Exception {
		Long zone = saveZone("F2구역", "1층", 1);
		Long draft = saveSchedule(savePerformance("DRAFT"), "ON_SALE");
		Long canceled = saveSchedule(savePerformance("PUBLISHED"), "CANCELED");

		for (Long scheduleId : List.of(draft, canceled)) {
			mockMvc.perform(get(SEATS_URL.formatted(scheduleId, zone)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE200"));
		}
	}
}
