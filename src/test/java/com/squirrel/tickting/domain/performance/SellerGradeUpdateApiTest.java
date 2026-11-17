package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 판매자의 등급 수정(SLR-10).
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호로 공연을 넣고,
 * 끝나면 그 사업체의 공연과 등급·회차만 지운다.
 * 판매 좌석을 만드는 기능(SLR-14)이 아직 없어서 회차의 seats_generated_at 은 SQL 로 넣는다.
 */
class SellerGradeUpdateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances/{performanceId}/grades/{gradeId}";
	private static final long MY_BUSINESS = 899L;
	private static final long OTHER_BUSINESS = 900L;

	@Autowired
	private RedissonClient redissonClient;

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

	/** 내 사업체 대표 토큰으로 보내는 등급 수정 요청 */
	private ResultActions update(Long performanceId, Long gradeId, Map<String, Object> body) throws Exception {
		return update(performanceId, gradeId, body, token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private ResultActions update(Long performanceId, Long gradeId, Map<String, Object> body, String authorization)
		throws Exception {
		return mockMvc.perform(put(URL, performanceId, gradeId)
			.header("Authorization", authorization)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	/** null 값도 본문에 넣으려고 Map.of 대신 LinkedHashMap 을 쓴다. */
	private Map<String, Object> body(String name, Integer price, String color) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("price", price);
		body.put("color", color);
		return body;
	}

	private Long insertPerformance(long businessId, String status) {
		Long venueId = jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '등급 수정 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?,
			        NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, status);
	}

	private Long insertGrade(Long performanceId, String name) {
		return jdbc.queryForObject("""
			INSERT INTO seat_grade (performance_id, name, price, color)
			VALUES (?, ?, 100000, '#E8503A')
			RETURNING id
			""", Long.class, performanceId, name);
	}

	private void insertSchedule(Long performanceId, String status, boolean seatsGenerated) {
		jdbc.update("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at,
			                                  updated_at)
			VALUES (?, NOW() + INTERVAL '30 days', ?, ?, NOW(), NOW())
			""", performanceId, status, seatsGenerated ? Timestamp.valueOf("2026-11-01 00:00:00") : null);
	}

	private Map<String, Object> savedGrade(Long gradeId) {
		return jdbc.queryForMap("SELECT name, price, color FROM seat_grade WHERE id = ?", gradeId);
	}

	/** 등급이 처음 넣은 값(이름, 100000원, #E8503A) 그대로인가 */
	private void assertUnchanged(Long gradeId, String name) {
		assertThat(savedGrade(gradeId)).containsEntry("name", name).containsEntry("price", 100000)
			.containsEntry("color", "#E8503A");
	}

	@Test
	@DisplayName("이름·가격·색을 전체 교체하고 명세의 모양으로 돌려주며, 색을 빼고 보내면 색이 지워진다")
	void replaced() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long gradeId = insertGrade(performanceId, "R석");

		String response = update(performanceId, gradeId, body(" VIP석 ", 150000, "#123abc"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<Map<String, Object>>read(response, "$.data")).containsExactlyInAnyOrderEntriesOf(Map.of(
			"gradeId", gradeId.intValue(), "name", "VIP석", "price", 150000, "color", "#123abc"));
		assertThat(savedGrade(gradeId)).containsEntry("name", "VIP석").containsEntry("price", 150000)
			.containsEntry("color", "#123abc");

		update(performanceId, gradeId, body("VIP석", 150000, null))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.color").value(nullValue()));
		assertThat(savedGrade(gradeId)).containsEntry("color", null);
	}

	@Test
	@DisplayName("같은 값으로 보내거나 자기 이름의 대소문자·띄어쓰기만 바꿔도 200이고, 담당자도 수정할 수 있다")
	void ownNameAndStaff() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long gradeId = insertGrade(performanceId, "R석");

		update(performanceId, gradeId, body("R석", 100000, "#E8503A")).andExpect(status().isOk());
		update(performanceId, gradeId, body("r 석", 100000, "#E8503A"), token(AccountType.SELLER, "STAFF", MY_BUSINESS))
			.andExpect(status().isOk());
		assertThat(savedGrade(gradeId)).containsEntry("name", "r 석");
	}

	@Test
	@DisplayName("입력 형식이 틀리면 400 COMMON000 이고 바뀌지 않는다 (추가와 같은 요청 규칙)")
	void invalidInput() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long gradeId = insertGrade(performanceId, "R석");

		for (Map<String, Object> body : List.of(
			body("  ", 1000, null), body("R석", null, null), body("R석", 10_000_001, null),
			body("R석", 1000, "#FFF"))) {
			update(performanceId, gradeId, body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON000"));
		}
		assertUnchanged(gradeId, "R석");
	}

	@Test
	@DisplayName("같은 공연의 다른 등급과 대소문자·공백을 무시하면 같은 이름이면 409 PERFORMANCE302 이고 바뀌지 않는다")
	void duplicatedName() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		insertGrade(performanceId, "R석");
		Long gradeId = insertGrade(performanceId, "S석");

		update(performanceId, gradeId, body("r 석", 1000, null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE302"));
		assertUnchanged(gradeId, "S석");
	}

	@Test
	@DisplayName("없는 등급이나 같은 사업체의 다른 공연 등급은 404 PERFORMANCE300 이다")
	void gradeNotFound() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long otherPerformance = insertPerformance(MY_BUSINESS, "DRAFT");
		Long otherGrade = insertGrade(otherPerformance, "R석");

		for (Long gradeId : List.of(otherGrade, Long.MAX_VALUE)) {
			update(performanceId, gradeId, body("VIP석", 1000, null))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE300"));
		}
		assertUnchanged(otherGrade, "R석");
	}

	@Test
	@DisplayName("취소된 공연은 409 PERFORMANCE002 이고 바뀌지 않는다")
	void canceledPerformance() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "CANCELED");
		Long gradeId = insertGrade(performanceId, "R석");

		update(performanceId, gradeId, body("VIP석", 1000, null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE002"));
		assertUnchanged(gradeId, "R석");
	}

	@Test
	@DisplayName("취소되지 않은 회차 중 하나라도 판매 좌석을 만들었으면 409 PERFORMANCE301 이고, 취소된 회차의 좌석만 있으면 고칠 수 있다")
	void locked() throws Exception {
		Long generated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(generated, "ON_SALE", false);
		insertSchedule(generated, "PAUSED", true);
		Long lockedGrade = insertGrade(generated, "R석");
		Long onlyCanceledGenerated = insertPerformance(MY_BUSINESS, "PUBLISHED");
		insertSchedule(onlyCanceledGenerated, "CANCELED", true);
		Long openGrade = insertGrade(onlyCanceledGenerated, "R석");

		update(generated, lockedGrade, body("R석", 90000, null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE301"));
		update(onlyCanceledGenerated, openGrade, body("R석", 90000, null)).andExpect(status().isOk());
		assertUnchanged(lockedGrade, "R석");
	}

	@Test
	@DisplayName("다른 사업체의 공연은 등급 번호가 맞고 취소·locked 상태여도 없는 공연과 같은 404 PERFORMANCE000 이다")
	void othersAndMissingAreNotFound() throws Exception {
		Long othersCanceled = insertPerformance(OTHER_BUSINESS, "CANCELED");
		Long canceledGrade = insertGrade(othersCanceled, "R석");
		Long othersLocked = insertPerformance(OTHER_BUSINESS, "PUBLISHED");
		insertSchedule(othersLocked, "ON_SALE", true);
		Long lockedGrade = insertGrade(othersLocked, "R석");

		update(othersCanceled, canceledGrade, body("VIP석", 1000, null))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		update(othersLocked, lockedGrade, body("VIP석", 1000, null))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		update(Long.MAX_VALUE, lockedGrade, body("VIP석", 1000, null))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		assertUnchanged(canceledGrade, "R석");
		assertUnchanged(lockedGrade, "R석");
	}

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanCall() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long gradeId = insertGrade(performanceId, "R석");

		update(performanceId, gradeId, body("VIP석", 1000, null), token(AccountType.USER, null, null))
			.andExpect(status().isForbidden());
		mockMvc.perform(put(URL, performanceId, gradeId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body("VIP석", 1000, null))))
			.andExpect(status().isUnauthorized());
		assertUnchanged(gradeId, "R석");
	}

	// ---- 동시성 ----

	@Test
	@DisplayName("서로 다른 두 등급을 동시에 같은 이름으로 바꾸면 공연 단위 락으로 순서대로 처리되어 하나만 200 이고 이름이 겹치지 않는다")
	void concurrentRenameToSameName() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		List<Long> gradeIds = List.of(insertGrade(performanceId, "A석"), insertGrade(performanceId, "B석"),
			insertGrade(performanceId, "C석"), insertGrade(performanceId, "D석"));

		List<MvcResult> results = runConcurrently(gradeIds.size(), i -> () ->
			update(performanceId, gradeIds.get(i), body("VIP석", 1000, null)).andReturn());

		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 200).hasSize(1);
		// 나머지는 이름 중복(PERFORMANCE302)이고, 락을 1초 안에 못 잡은 요청이 있으면 COMMON004 다. 둘 다 409 다.
		assertThat(results).filteredOn(result -> result.getResponse().getStatus() != 200)
			.allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_grade WHERE performance_id = ? AND name = 'VIP석'",
			Integer.class, performanceId)).isEqualTo(1);
	}

	@Test
	@DisplayName("판매 좌석 생성 같은 다른 작업이 공연 단위 락(performance:{공연 번호})을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고 바뀌지 않는다")
	void lockHeldByOthers() throws Exception {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT");
		Long gradeId = insertGrade(performanceId, "R석");
		RLock lock = redissonClient.getLock("tickting:lock:performance:" + performanceId);
		// 락은 잡은 스레드의 것이라, 요청을 보내는 테스트 스레드가 아닌 다른 스레드에서 잡아야 기다리게 된다.
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			update(performanceId, gradeId, body("R석", 90000, null))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMON004"));
		} finally {
			lock.forceUnlock();
		}
		assertUnchanged(gradeId, "R석");
	}
}
