package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
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

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 대표 토큰으로 보내는 조회 요청 */
	private MockHttpServletRequestBuilder request(Long performanceId) {
		return get(URL, performanceId).header("Authorization", token(AccountType.SELLER, "OWNER", MY_BUSINESS));
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

	private Long insertGrade(Long performanceId, String name, int price) {
		return jdbc.queryForObject("""
			INSERT INTO seat_grade (performance_id, name, price)
			VALUES (?, ?, ?)
			RETURNING id
			""", Long.class, performanceId, name, price);
	}

	private Long zoneId(String name) {
		return jdbc.queryForObject("SELECT id FROM venue_zone WHERE venue_id = ? AND name = ?", Long.class,
			arenaVenueId(), name);
	}

	private void setZone(Long performanceId, Long zoneId, String status, Long gradeId) {
		jdbc.update("UPDATE zone_grade SET assignment_status = ?, grade_id = ? WHERE performance_id = ? AND zone_id = ?",
			status, gradeId, performanceId, zoneId);
	}

	private void insertSchedule(Long performanceId, String status, boolean seatsGenerated) {
		jdbc.update("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at,
			                                  updated_at)
			VALUES (?, NOW() + INTERVAL '30 days', ?, ?, NOW(), NOW())
			""", performanceId, status, seatsGenerated ? Timestamp.valueOf("2026-11-01 00:00:00") : null);
	}

	private boolean locked(Long performanceId) throws Exception {
		String body = mockMvc.perform(request(performanceId)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.data.locked");
	}

	@Test
	@DisplayName("등급과 구역 설정이 명세의 모양으로 나오고, 구역에는 공연장의 이름·층·순서·좌석 수가 붙는다")
	void responseShape() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		initializeZones(performanceId);
		Long vip = insertGrade(performanceId, "VIP", 165000);
		Long f2 = zoneId("F2구역");
		setZone(performanceId, f2, "ASSIGNED", vip);

		String body = mockMvc.perform(request(performanceId)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		Map<String, Object> grade = JsonPath.read(body, "$.data.grades[0]");
		Map<String, Object> zone = JsonPath.<List<Map<String, Object>>>read(body,
			"$.data.zones[?(@.zoneId == " + f2 + ")]").getFirst();
		long seatCount = jdbc.queryForObject("SELECT COUNT(*) FROM venue_seat WHERE zone_id = ?", Long.class, f2);

		assertThat(JsonPath.<Map<String, Object>>read(body, "$.data").keySet())
			.containsExactlyInAnyOrder("locked", "grades", "zones");
		assertThat(JsonPath.<Boolean>read(body, "$.data.locked")).isFalse();
		assertThat(grade).containsExactlyInAnyOrderEntriesOf(Map.of(
			"gradeId", vip.intValue(), "name", "VIP", "price", 165000));
		assertThat(zone.keySet()).containsExactlyInAnyOrder("zoneId", "name", "floor", "sortOrder", "seatCount",
			"assignmentStatus", "gradeId");
		assertThat(zone.get("name")).isEqualTo("F2구역");
		assertThat(zone.get("floor")).isEqualTo("1층");
		assertThat(zone.get("sortOrder")).isEqualTo(3);
		assertThat(((Number) zone.get("seatCount")).longValue()).isEqualTo(seatCount).isPositive();
		assertThat(zone.get("assignmentStatus")).isEqualTo("ASSIGNED");
		assertThat(zone.get("gradeId")).isEqualTo(vip.intValue());
	}

	@Test
	@DisplayName("등급은 가격 높은 순(같으면 번호순), 구역은 저장 순서와 상관없이 공연장 구역 순서(sortOrder)순이다")
	void sorted() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		initializeZones(performanceId);
		insertGrade(performanceId, "S", 99000);
		insertGrade(performanceId, "VIP", 165000);
		insertGrade(performanceId, "R", 132000);
		insertGrade(performanceId, "A", 99000);

		String body = mockMvc.perform(request(performanceId)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		List<Integer> sortOrders = JsonPath.read(body, "$.data.zones[*].sortOrder");
		List<Long> expectedZoneIds = jdbc.queryForList(
			"SELECT id FROM venue_zone WHERE venue_id = ? ORDER BY sort_order, id", Long.class, arenaVenueId());

		assertThat(JsonPath.<List<String>>read(body, "$.data.grades[*].name"))
			.containsExactly("VIP", "R", "S", "A");
		assertThat(sortOrders).isSorted();
		assertThat(JsonPath.<List<Number>>read(body, "$.data.zones[*].zoneId").stream().map(Number::longValue))
			.containsExactlyElementsOf(expectedZoneIds);
	}

	@Test
	@DisplayName("ASSIGNED 구역만 gradeId 가 있고 UNSET·INTENTIONALLY_UNSOLD 구역은 gradeId 가 null 이다")
	void gradeIdOnlyForAssigned() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		initializeZones(performanceId);
		Long vip = insertGrade(performanceId, "VIP", 165000);
		setZone(performanceId, zoneId("F1구역"), "ASSIGNED", vip);
		setZone(performanceId, zoneId("F2구역"), "INTENTIONALLY_UNSOLD", null);

		mockMvc.perform(request(performanceId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.zones[?(@.name == 'F1구역')].gradeId").value(vip.intValue()))
			.andExpect(jsonPath("$.data.zones[?(@.name == 'F2구역')].assignmentStatus").value("INTENTIONALLY_UNSOLD"))
			.andExpect(jsonPath("$.data.zones[?(@.name == 'F2구역')].gradeId").value(contains(nullValue())))
			.andExpect(jsonPath("$.data.zones[?(@.name == 'F3구역')].assignmentStatus").value("UNSET"))
			.andExpect(jsonPath("$.data.zones[?(@.name == 'F3구역')].gradeId").value(contains(nullValue())));
	}

	@Test
	@DisplayName("등급이 없으면 grades 는 빈 배열이고, 구역은 구역 설정 행 수만큼 나온다")
	void noGrades() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		initializeZones(performanceId);
		int zoneRows = jdbc.queryForObject("SELECT COUNT(*) FROM zone_grade WHERE performance_id = ?", Integer.class,
			performanceId);

		mockMvc.perform(request(performanceId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.grades").isEmpty())
			.andExpect(jsonPath("$.data.zones.length()").value(zoneRows));
	}

	@Test
	@DisplayName("locked 는 취소되지 않은 회차 중 하나라도 판매 좌석을 만들었을 때만 true 다")
	void lockedIgnoresCanceledSchedules() throws Exception {
		Long noSchedule = insertPerformance(MY_BUSINESS, "PUBLISHED");

		Long notGenerated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(notGenerated, "ON_SALE", false);

		Long generated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(generated, "ON_SALE", false);
		insertSchedule(generated, "POSTPONED", true);

		Long onlyCanceledGenerated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(onlyCanceledGenerated, "CANCELED", true);
		insertSchedule(onlyCanceledGenerated, "ON_SALE", false);

		Long canceledAndNormalGenerated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(canceledAndNormalGenerated, "CANCELED", true);
		insertSchedule(canceledAndNormalGenerated, "PAUSED", true);

		assertThat(locked(noSchedule)).isFalse();
		assertThat(locked(notGenerated)).isFalse();
		assertThat(locked(generated)).isTrue();
		assertThat(locked(onlyCanceledGenerated)).isFalse();
		assertThat(locked(canceledAndNormalGenerated)).isTrue();
	}

	@Test
	@DisplayName("담당자 토큰으로도 조회할 수 있고, 취소된 공연도 조회할 수 있다")
	void staffAndCanceledPerformance() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "CANCELED");
		initializeZones(performanceId);

		mockMvc.perform(get(URL, performanceId)
				.header("Authorization", token(AccountType.SELLER, "STAFF", MY_BUSINESS)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.zones").isNotEmpty());
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

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanCall() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");

		mockMvc.perform(get(URL, performanceId).header("Authorization", token(AccountType.USER, null, null)))
			.andExpect(status().isForbidden());
		mockMvc.perform(get(URL, performanceId)).andExpect(status().isUnauthorized());
	}
}
