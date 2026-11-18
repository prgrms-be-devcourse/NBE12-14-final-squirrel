package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 구역 상태·등급 설정(SLR-11).
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호로 공연을 넣고,
 * 끝나면 그 사업체의 공연과 등급·구역 설정·회차만 지운다.
 * 공연 등록 API 대신 SQL 로 공연을 넣으므로 등록이 만드는 구역 설정(UNSET)도 SQL 로 만든다.
 * 판매 좌석을 만드는 기능(SLR-14)이 아직 없어서 회차의 seats_generated_at 은 SQL 로 넣는다.
 * 시드 공연장의 구역 수·번호는 코드에 적지 않고 SQL 로 읽어 쓴다.
 */
class SellerZoneGradeUpdateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances/{performanceId}/zone-grades";
	private static final long MY_BUSINESS = 879L;
	private static final long OTHER_BUSINESS = 880L;

	@Autowired
	private RedissonClient redissonClient;

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

	/** 내 사업체 대표 토큰으로 보내는 구역 설정 요청 */
	private ResultActions send(Long performanceId, Object body) throws Exception {
		return send(performanceId, body, token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private ResultActions send(Long performanceId, Object body, String authorization) throws Exception {
		return mockMvc.perform(patch(URL, performanceId)
			.header("Authorization", authorization)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private Map<String, Object> request(Map<?, ?>... items) {
		return Map.of("assignments", List.of(items));
	}

	/** null 값도 본문에 넣으려고 Map.of 대신 LinkedHashMap 을 쓴다. */
	private Map<String, Object> item(Long zoneId, String status, Long gradeId) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("zoneId", zoneId);
		item.put("assignmentStatus", status);
		item.put("gradeId", gradeId);
		return item;
	}

	/** 공연을 넣고, 공연 등록처럼 공연장의 모든 구역 설정을 UNSET 으로 만든다. */
	private Long insertPerformance(long businessId, String status) {
		Long venueId = jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '구역 설정 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?,
			        NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, status);
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, assignment_status)
			SELECT ?, z.id, 'UNSET' FROM venue_zone z WHERE z.venue_id = ?
			""", performanceId, venueId);
		return performanceId;
	}

	/** 공연장 구역 번호. 응답의 구역 순서(sortOrder, 같으면 번호순)와 같은 순서다. */
	private List<Long> zoneIds(Long performanceId) {
		return jdbc.queryForList("""
			SELECT z.id FROM venue_zone z
			WHERE z.venue_id = (SELECT venue_id FROM performance WHERE id = ?)
			ORDER BY z.sort_order, z.id
			""", Long.class, performanceId);
	}

	private Long insertGrade(Long performanceId, String name, int price) {
		return jdbc.queryForObject("""
			INSERT INTO seat_grade (performance_id, name, price)
			VALUES (?, ?, ?)
			RETURNING id
			""", Long.class, performanceId, name, price);
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

	/** 구역의 저장된 상태와 등급 번호. ("상태:등급 번호", 등급이 없으면 "상태:null") */
	private String savedZone(Long performanceId, Long zoneId) {
		return jdbc.queryForObject(
			"SELECT assignment_status || ':' || COALESCE(grade_id::text, 'null') FROM zone_grade "
				+ "WHERE performance_id = ? AND zone_id = ?", String.class, performanceId, zoneId);
	}

	/** 공연의 모든 구역이 처음 넣은 UNSET 그대로인가 */
	private void assertAllUnset(Long performanceId) {
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM zone_grade WHERE performance_id = ? AND (assignment_status <> 'UNSET' OR grade_id IS NOT NULL)",
			Integer.class, performanceId)).isZero();
	}

	@Test
	@DisplayName("요청한 구역만 바뀌고 빠진 구역은 그대로이며, 응답은 등급·구역 조회와 같은 모양이다")
	void onlyRequestedZonesChange() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long vipGrade = insertGrade(performanceId, "VIP", 150000);
		List<Long> zones = zoneIds(performanceId);
		setZone(performanceId, zones.get(2), "ASSIGNED", rGrade);

		String response = send(performanceId, request(
			item(zones.get(1), "ASSIGNED", vipGrade),
			item(zones.get(0), "INTENTIONALLY_UNSOLD", null)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.locked").value(false))
			.andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<List<Integer>>read(response, "$.data.grades[*].gradeId"))
			.containsExactly(vipGrade.intValue(), rGrade.intValue());
		assertThat(JsonPath.<List<Integer>>read(response, "$.data.zones[*].zoneId"))
			.containsExactlyElementsOf(zones.stream().map(Long::intValue).toList());
		assertThat(JsonPath.<Map<String, Object>>read(response, "$.data.zones[1]"))
			.containsEntry("assignmentStatus", "ASSIGNED").containsEntry("gradeId", vipGrade.intValue())
			.containsKeys("name", "floor", "sortOrder", "seatCount");
		assertThat(savedZone(performanceId, zones.get(0))).isEqualTo("INTENTIONALLY_UNSOLD:null");
		assertThat(savedZone(performanceId, zones.get(1))).isEqualTo("ASSIGNED:" + vipGrade);
		assertThat(savedZone(performanceId, zones.get(2))).isEqualTo("ASSIGNED:" + rGrade);
		assertThat(savedZone(performanceId, zones.get(3))).isEqualTo("UNSET:null");
	}

	@Test
	@DisplayName("ASSIGNED 구역을 UNSET·INTENTIONALLY_UNSOLD 로 되돌리면 등급이 비워지고, UNSET 을 ASSIGNED 로 바꿀 수 있다")
	void revertAndAssign() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		List<Long> zones = zoneIds(performanceId);
		setZone(performanceId, zones.get(0), "ASSIGNED", rGrade);
		setZone(performanceId, zones.get(1), "ASSIGNED", rGrade);

		send(performanceId, request(
			item(zones.get(0), "UNSET", null),
			item(zones.get(1), "INTENTIONALLY_UNSOLD", null),
			item(zones.get(2), "ASSIGNED", rGrade)))
			.andExpect(status().isOk());

		assertThat(savedZone(performanceId, zones.get(0))).isEqualTo("UNSET:null");
		assertThat(savedZone(performanceId, zones.get(1))).isEqualTo("INTENTIONALLY_UNSOLD:null");
		assertThat(savedZone(performanceId, zones.get(2))).isEqualTo("ASSIGNED:" + rGrade);
	}

	@Test
	@DisplayName("값이 그대로인 요청도 200 이고, 담당자도 설정할 수 있다")
	void sameValueAndStaff() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long zoneId = zoneIds(performanceId).get(0);
		setZone(performanceId, zoneId, "ASSIGNED", rGrade);

		send(performanceId, request(item(zoneId, "ASSIGNED", rGrade))).andExpect(status().isOk());
		send(performanceId, request(item(zoneId, "INTENTIONALLY_UNSOLD", null)),
			token(AccountType.SELLER, "STAFF", MY_BUSINESS))
			.andExpect(status().isOk());
		assertThat(savedZone(performanceId, zoneId)).isEqualTo("INTENTIONALLY_UNSOLD:null");
	}

	@Test
	@DisplayName("빈 목록·목록 없음·빈 항목·값 누락·모르는 상태·같은 구역 중복은 400 COMMON000 이고 아무것도 바뀌지 않는다")
	void invalidInput() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long zoneId = zoneIds(performanceId).get(0);
		Map<String, Object> nullList = new LinkedHashMap<>();
		nullList.put("assignments", null);
		Map<String, Object> nullItem = new LinkedHashMap<>();
		nullItem.put("assignments", Collections.singletonList(null));

		for (Object body : List.of(
			Map.of("assignments", List.of()), Map.of(), nullList, nullItem,
			request(item(null, "UNSET", null)),
			request(item(zoneId, null, null)),
			request(item(zoneId, "SOLD", null)),
			request(item(zoneId, "ASSIGNED", rGrade), item(zoneId, "UNSET", null)))) {
			send(performanceId, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON000"));
		}
		assertAllUnset(performanceId);
	}

	@Test
	@DisplayName("상태와 gradeId 가 맞지 않으면 400 PERFORMANCE306 이고, 남의 공연에 보내도 요청만 보고 판단해 404 가 아닌 400 이다")
	void assignmentInvalid() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long zoneId = zoneIds(performanceId).get(0);
		Long others = insertPerformance(OTHER_BUSINESS, "DRAFT");

		for (Map<String, Object> body : List.of(
			request(item(zoneId, "ASSIGNED", null)),
			request(item(zoneId, "UNSET", rGrade)),
			request(item(zoneId, "INTENTIONALLY_UNSOLD", rGrade)))) {
			send(performanceId, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PERFORMANCE306"));
		}
		send(others, request(item(zoneId, "ASSIGNED", null)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PERFORMANCE306"));
		assertAllUnset(performanceId);
	}

	@Test
	@DisplayName("이 공연의 공연장에 없는 구역은 400 PERFORMANCE304 이고 같은 요청의 정상 항목도 바뀌지 않는다")
	void zoneNotInVenue() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long zoneId = zoneIds(performanceId).get(0);
		Long otherVenueZone = jdbc.queryForObject("""
			SELECT z.id FROM venue_zone z
			WHERE z.venue_id <> (SELECT venue_id FROM performance WHERE id = ?)
			ORDER BY z.id LIMIT 1
			""", Long.class, performanceId);

		for (Long wrongZone : List.of(otherVenueZone, Long.MAX_VALUE)) {
			send(performanceId, request(item(zoneId, "ASSIGNED", rGrade), item(wrongZone, "UNSET", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PERFORMANCE304"));
		}
		assertAllUnset(performanceId);
	}

	@Test
	@DisplayName("없는 등급이나 같은 사업체의 다른 공연 등급은 400 PERFORMANCE305 이고 같은 요청의 정상 항목도 바뀌지 않는다")
	void gradeNotInPerformance() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long otherPerformance = insertPerformance(MY_BUSINESS, "DRAFT");
		Long otherGrade = insertGrade(otherPerformance, "R", 100000);
		List<Long> zones = zoneIds(performanceId);

		for (Long wrongGrade : List.of(otherGrade, Long.MAX_VALUE)) {
			send(performanceId, request(item(zones.get(0), "ASSIGNED", rGrade), item(zones.get(1), "ASSIGNED", wrongGrade)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PERFORMANCE305"));
		}
		assertAllUnset(performanceId);
	}

	@Test
	@DisplayName("요청을 적용한 뒤 전석과 다른 등급이 함께 구역에 지정되면 409 PERFORMANCE303 이고, 요청에 없는 구역도 함께 본다")
	void allMixed() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long allGrade = insertGrade(performanceId, "ALL", 50000);
		List<Long> zones = zoneIds(performanceId);
		setZone(performanceId, zones.get(0), "ASSIGNED", rGrade);

		// 이미 R 구역이 있는 공연에 다른 구역을 전석으로
		send(performanceId, request(item(zones.get(1), "ASSIGNED", allGrade)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE303"));
		// 한 요청 안에서 전석과 R 을 함께
		send(performanceId, request(item(zones.get(0), "UNSET", null),
			item(zones.get(1), "ASSIGNED", allGrade), item(zones.get(2), "ASSIGNED", rGrade)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE303"));
		assertThat(savedZone(performanceId, zones.get(0))).isEqualTo("ASSIGNED:" + rGrade);
		assertThat(savedZone(performanceId, zones.get(1))).isEqualTo("UNSET:null");
		assertThat(savedZone(performanceId, zones.get(2))).isEqualTo("UNSET:null");
	}

	@Test
	@DisplayName("R·S 구역을 한 요청에서 모두 전석으로 바꾸면 200 이고, 구역에 지정하지 않은 등급은 세지 않는다")
	void allAllowedWhenNotMixed() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long sGrade = insertGrade(performanceId, "S", 80000);
		Long allGrade = insertGrade(performanceId, "ALL", 50000);
		List<Long> zones = zoneIds(performanceId);
		setZone(performanceId, zones.get(0), "ASSIGNED", rGrade);
		setZone(performanceId, zones.get(1), "ASSIGNED", sGrade);

		send(performanceId, request(item(zones.get(0), "ASSIGNED", allGrade), item(zones.get(1), "ASSIGNED", allGrade),
			item(zones.get(2), "ASSIGNED", allGrade)))
			.andExpect(status().isOk());
		assertThat(savedZone(performanceId, zones.get(0))).isEqualTo("ASSIGNED:" + allGrade);
		assertThat(savedZone(performanceId, zones.get(1))).isEqualTo("ASSIGNED:" + allGrade);
	}

	@Test
	@DisplayName("취소된 공연은 409 PERFORMANCE002 이고 바뀌지 않는다")
	void canceledPerformance() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "CANCELED");
		Long rGrade = insertGrade(performanceId, "R", 100000);

		send(performanceId, request(item(zoneIds(performanceId).get(0), "ASSIGNED", rGrade)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE002"));
		assertAllUnset(performanceId);
	}

	@Test
	@DisplayName("취소되지 않은 회차 중 하나라도 판매 좌석을 만들었으면 409 PERFORMANCE301 이고, 취소된 회차의 좌석만 있으면 설정할 수 있다")
	void locked() throws Exception {
		Long generated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(generated, "ON_SALE", false);
		insertSchedule(generated, "POSTPONED", true);
		Long lockedGrade = insertGrade(generated, "R", 100000);
		Long onlyCanceledGenerated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(onlyCanceledGenerated, "CANCELED", true);
		Long openGrade = insertGrade(onlyCanceledGenerated, "R", 100000);

		send(generated, request(item(zoneIds(generated).get(0), "ASSIGNED", lockedGrade)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE301"));
		send(onlyCanceledGenerated, request(item(zoneIds(onlyCanceledGenerated).get(0), "ASSIGNED", openGrade)))
			.andExpect(status().isOk());
		assertAllUnset(generated);
	}

	@Test
	@DisplayName("판매 좌석을 만든 공연에 없는 구역을 보내면 locked 409 보다 400 PERFORMANCE304 가 먼저다")
	void zoneCheckBeforeLocked() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(performanceId, "ON_SALE", true);

		send(performanceId, request(item(Long.MAX_VALUE, "UNSET", null)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PERFORMANCE304"));
	}

	@Test
	@DisplayName("다른 사업체의 공연은 취소·locked 상태여도 없는 공연과 같은 404 PERFORMANCE000 이고 바뀌지 않는다")
	void othersAndMissingAreNotFound() throws Exception {
		Long othersCanceled = insertPerformance(OTHER_BUSINESS, "CANCELED");
		Long canceledGrade = insertGrade(othersCanceled, "R", 100000);
		Long othersLocked = insertPerformance(OTHER_BUSINESS, "PUBLISHED");
		insertSchedule(othersLocked, "ON_SALE", true);
		Long lockedGrade = insertGrade(othersLocked, "R", 100000);

		send(othersCanceled, request(item(zoneIds(othersCanceled).get(0), "ASSIGNED", canceledGrade)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		send(othersLocked, request(item(zoneIds(othersLocked).get(0), "ASSIGNED", lockedGrade)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		send(Long.MAX_VALUE, request(item(zoneIds(othersLocked).get(0), "ASSIGNED", lockedGrade)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		assertAllUnset(othersCanceled);
		assertAllUnset(othersLocked);
	}

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanCall() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Map<String, Object> body = request(item(zoneIds(performanceId).get(0), "ASSIGNED", rGrade));

		send(performanceId, body, token(AccountType.USER, null, null)).andExpect(status().isForbidden());
		mockMvc.perform(patch(URL, performanceId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isUnauthorized());
		assertAllUnset(performanceId);
	}

	// ---- 동시성 ----

	@Test
	@DisplayName("서로 다른 구역을 동시에 전석과 R 로 지정하면 공연 단위 락으로 순서대로 처리되어 하나만 200 이고 전석과 다른 등급이 함께 지정되지 않는다")
	void concurrentAllAndOtherGrade() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		Long allGrade = insertGrade(performanceId, "ALL", 50000);
		List<Long> zones = zoneIds(performanceId);
		List<Map<String, Object>> bodies = List.of(
			request(item(zones.get(0), "ASSIGNED", allGrade)),
			request(item(zones.get(1), "ASSIGNED", rGrade)));

		List<MvcResult> results = runConcurrently(bodies.size(), i -> () ->
			send(performanceId, bodies.get(i)).andReturn());

		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 200).hasSize(1);
		// 나머지는 전석 섞임(PERFORMANCE303)이고, 락을 1초 안에 못 잡았으면 COMMON004 다. 둘 다 409 다.
		assertThat(results).filteredOn(result -> result.getResponse().getStatus() != 200)
			.allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(DISTINCT grade_id) FROM zone_grade WHERE performance_id = ? AND assignment_status = 'ASSIGNED'",
			Integer.class, performanceId)).isEqualTo(1);
	}

	@Test
	@DisplayName("판매 좌석 생성 같은 다른 작업이 공연 단위 락(performance:{공연 번호})을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고 바뀌지 않는다")
	void lockHeldByOthers() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long rGrade = insertGrade(performanceId, "R", 100000);
		RLock lock = redissonClient.getLock("tickting:lock:performance:" + performanceId);
		// 락은 잡은 스레드의 것이라, 요청을 보내는 테스트 스레드가 아닌 다른 스레드에서 잡아야 기다리게 된다.
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			send(performanceId, request(item(zoneIds(performanceId).get(0), "ASSIGNED", rGrade)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMON004"));
		} finally {
			lock.forceUnlock();
		}
		assertAllUnset(performanceId);
	}
}
