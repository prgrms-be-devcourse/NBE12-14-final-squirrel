package com.squirrel.tickting.domain.performance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구역 요약(PFM-03). 공연장·구역·좌석·공연·등급·회차·판매 좌석을 SQL 로 직접 넣고 확인한다.
 * (공연장 생성·판매 좌석 생성 API 는 다른 파트 작업이라 기대지 않는다)
 * 테스트 트랜잭션 안에서 넣고 끝나면 되돌려서 시드 데이터는 그대로 남는다.
 */
@Transactional
class UserZoneSummaryApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/schedules/%d/zones";

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

	/** 구역에 좌석을 count 개 만들고 번호 목록을 돌려준다. */
	private List<Long> saveVenueSeats(Long zoneId, int count) {
		List<Long> ids = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			ids.add(jdbc.queryForObject("""
				INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order)
				VALUES (?, ?, '1열', ?, ?) RETURNING id
				""", Long.class, venueId, zoneId, String.valueOf(i), i));
		}
		return ids;
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

	private Long saveGrade(Long performanceId, String name, int price, String color) {
		return jdbc.queryForObject("""
			INSERT INTO seat_grade (performance_id, name, price, color) VALUES (?, ?, ?, ?) RETURNING id
			""", Long.class, performanceId, name, price, color);
	}

	private void assignZone(Long performanceId, Long zoneId, Long gradeId) {
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, grade_id, assignment_status) VALUES (?, ?, ?, 'ASSIGNED')
			""", performanceId, zoneId, gradeId);
	}

	private void leaveZoneUnsold(Long performanceId, Long zoneId) {
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, grade_id, assignment_status)
			VALUES (?, ?, NULL, 'INTENTIONALLY_UNSOLD')
			""", performanceId, zoneId);
	}

	/** 판매 좌석을 이미 만든 회차. */
	private Long saveSchedule(Long performanceId, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?) RETURNING id
			""", Long.class, performanceId, now.plusDays(7), status, now, now, now);
	}

	/** 판매 좌석을 아직 만들지 않은 회차 (판매 오픈 24시간 전보다 이전). */
	private Long saveScheduleWithoutSeats(Long performanceId) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, 'ON_SALE', ?, ?) RETURNING id
			""", Long.class, performanceId, now.plusDays(7), now, now);
	}

	private void saveScheduleSeat(Long scheduleId, Long venueSeatId, Long zoneId, String status) {
		jdbc.update("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, status, version, updated_at)
			VALUES (?, ?, ?, ?, 0, ?)
			""", scheduleId, venueSeatId, zoneId, status, now);
	}

	// ---- 테스트 ----

	@Test
	@DisplayName("로그인 없이 구역 요약을 보고, 구역은 화면 순서대로 등급·색·가격과 잔여석·전체 좌석 수가 온다")
	void zoneSummaryWithoutLogin() throws Exception {
		// 화면 순서(sort_order)가 번호 순서와 반대가 되게 넣는다
		Long second = saveZone("F3구역", "1층", 2);
		Long first = saveZone("F2구역", "1층", 1);
		List<Long> firstSeats = saveVenueSeats(first, 5);
		List<Long> secondSeats = saveVenueSeats(second, 2);

		Long performanceId = savePerformance("PUBLISHED");
		assignZone(performanceId, first, saveGrade(performanceId, "R석", 154000, "#F2994A"));
		assignZone(performanceId, second, saveGrade(performanceId, "S석", 132000, "#2D9CDB"));

		Long scheduleId = saveSchedule(performanceId, "ON_SALE");
		// 살 수 있는 좌석(AVAILABLE)만 잔여석이다. 임시 점유·판매 완료·판매자 보류는 빠진다
		saveScheduleSeat(scheduleId, firstSeats.get(0), first, "AVAILABLE");
		saveScheduleSeat(scheduleId, firstSeats.get(1), first, "AVAILABLE");
		saveScheduleSeat(scheduleId, firstSeats.get(2), first, "HELD");
		saveScheduleSeat(scheduleId, firstSeats.get(3), first, "SOLD");
		saveScheduleSeat(scheduleId, firstSeats.get(4), first, "RESERVED");
		saveScheduleSeat(scheduleId, secondSeats.get(0), second, "SOLD");
		saveScheduleSeat(scheduleId, secondSeats.get(1), second, "SOLD");

		mockMvc.perform(get(URL.formatted(scheduleId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.scheduleId").value(scheduleId))
			.andExpect(jsonPath("$.data.layoutKey").value("sample-arena"))
			.andExpect(jsonPath("$.data.zones.length()").value(2))
			.andExpect(jsonPath("$.data.zones[0].zoneId").value(first))
			.andExpect(jsonPath("$.data.zones[0].name").value("F2구역"))
			.andExpect(jsonPath("$.data.zones[0].floor").value("1층"))
			.andExpect(jsonPath("$.data.zones[0].grade").value("R석"))
			.andExpect(jsonPath("$.data.zones[0].color").value("#F2994A"))
			.andExpect(jsonPath("$.data.zones[0].price").value(154000))
			.andExpect(jsonPath("$.data.zones[0].remaining").value(2))
			.andExpect(jsonPath("$.data.zones[0].total").value(5))
			.andExpect(jsonPath("$.data.zones[1].zoneId").value(second))
			.andExpect(jsonPath("$.data.zones[1].grade").value("S석"))
			.andExpect(jsonPath("$.data.zones[1].remaining").value(0))
			.andExpect(jsonPath("$.data.zones[1].total").value(2));
	}

	@Test
	@DisplayName("판매하지 않는 구역은 등급·색·가격이 null 이고 잔여석은 0, 전체 좌석 수는 그대로 보인다")
	void unsoldZone() throws Exception {
		Long zone = saveZone("시야 제한석", null, 1);
		List<Long> seats = saveVenueSeats(zone, 3);
		Long performanceId = savePerformance("PUBLISHED");
		leaveZoneUnsold(performanceId, zone);

		Long scheduleId = saveSchedule(performanceId, "ON_SALE");
		seats.forEach(seat -> saveScheduleSeat(scheduleId, seat, zone, "BLOCKED"));

		mockMvc.perform(get(URL.formatted(scheduleId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.zones[0].name").value("시야 제한석"))
			.andExpect(jsonPath("$.data.zones[0].floor").isEmpty())
			.andExpect(jsonPath("$.data.zones[0].grade").isEmpty())
			.andExpect(jsonPath("$.data.zones[0].color").isEmpty())
			.andExpect(jsonPath("$.data.zones[0].price").isEmpty())
			.andExpect(jsonPath("$.data.zones[0].remaining").value(0))
			.andExpect(jsonPath("$.data.zones[0].total").value(3));
	}

	@Test
	@DisplayName("판매 좌석을 아직 만들지 않은 회차는 구역·등급은 보이고 잔여석이 모두 0, 전체 좌석 수는 공연장 좌석 수다")
	void seatsNotGeneratedYet() throws Exception {
		venueId = saveVenue(null);
		Long zone = saveZone("F2구역", "1층", 1);
		saveVenueSeats(zone, 4);
		Long performanceId = savePerformance("PUBLISHED");
		assignZone(performanceId, zone, saveGrade(performanceId, "R석", 154000, "#F2994A"));

		Long scheduleId = saveScheduleWithoutSeats(performanceId);

		mockMvc.perform(get(URL.formatted(scheduleId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.layoutKey").isEmpty())
			.andExpect(jsonPath("$.data.zones[0].grade").value("R석"))
			.andExpect(jsonPath("$.data.zones[0].price").value(154000))
			.andExpect(jsonPath("$.data.zones[0].remaining").value(0))
			.andExpect(jsonPath("$.data.zones[0].total").value(4));
	}

	@Test
	@DisplayName("일시 중지·연기된 회차의 구역 요약은 볼 수 있다")
	void pausedAndPostponedSchedule() throws Exception {
		Long performanceId = savePerformance("PUBLISHED");

		for (String scheduleStatus : List.of("PAUSED", "POSTPONED")) {
			mockMvc.perform(get(URL.formatted(saveSchedule(performanceId, scheduleStatus))))
				.andExpect(status().isOk());
		}
	}

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

	@Test
	@DisplayName("회차 번호가 숫자가 아니면 400 COMMON000 이다")
	void invalidScheduleId() throws Exception {
		mockMvc.perform(get("/api/v1/schedules/abc/zones"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}
}
