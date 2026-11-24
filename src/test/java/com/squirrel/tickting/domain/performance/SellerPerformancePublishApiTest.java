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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 공연 공개(SLR-04).
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호로 공연을 넣고,
 * 끝나면 그 사업체의 공연과 등급·구역 설정·회차·규정만 지운다.
 * 공연 등록·회차 추가 API 대신 SQL 로 넣으므로 등록이 만드는 구역 설정(UNSET)도 SQL 로 만든다.
 * 시각은 DB 의 NOW() 가 아니라 자바 시각으로 계산해서 넣는다. (서비스가 자바 시각을 기준으로 비교한다)
 */
class SellerPerformancePublishApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances/{performanceId}/publish";
	private static final long MY_BUSINESS = 877L;
	private static final long OTHER_BUSINESS = 878L;

	@Autowired
	private RedissonClient redissonClient;

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM zone_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
		jdbc.update("DELETE FROM cancel_policy_rule WHERE policy_id IN (SELECT id FROM cancel_policy WHERE business_id IN (?, ?))",
			MY_BUSINESS, OTHER_BUSINESS);
		jdbc.update("DELETE FROM cancel_policy WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
	}

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 공개는 대표 전용이 아니라서 담당자 토큰으로 보낸다. */
	private ResultActions publish(Long performanceId) throws Exception {
		return mockMvc.perform(post(URL, performanceId)
			.header("Authorization", token(AccountType.SELLER, "STAFF", MY_BUSINESS)));
	}

	private Long defaultPolicyId() {
		return jdbc.queryForObject("SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
	}

	private Long insertMyPolicy() {
		return jdbc.queryForObject(
			"INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, '공개 테스트 규정', NOW()) RETURNING id",
			Long.class, MY_BUSINESS);
	}

	/** 공연을 넣고, 공연 등록처럼 공연장의 모든 구역 설정을 UNSET 으로 만든다. 판매 필드는 비워 둔다. */
	private Long insertPerformance(long businessId, String status, Long policyId) {
		Long venueId = jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '공개 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?,
			        NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, status);
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, assignment_status)
			SELECT ?, z.id, 'UNSET' FROM venue_zone z WHERE z.venue_id = ?
			""", performanceId, venueId);
		return performanceId;
	}

	/**
	 * 모든 조건을 만족하는 작성 중 공연. 판매는 하루 뒤 열려 열흘 뒤 닫히고, 회차는 20일 뒤 하나다.
	 * 구역은 둘째(sortOrder 순)만 의도적 미판매이고 나머지는 R 석이다.
	 */
	private Long insertReadyPerformance() {
		Long performanceId = insertPerformance(MY_BUSINESS, "DRAFT", defaultPolicyId());
		setSale(performanceId, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(10));
		Long rGrade = insertGrade(performanceId, "R");
		List<Long> zones = zoneIds(performanceId);
		for (int i = 0; i < zones.size(); i++) {
			if (i == 1) {
				setZone(performanceId, zones.get(i), "INTENTIONALLY_UNSOLD", null);
			} else {
				setZone(performanceId, zones.get(i), "ASSIGNED", rGrade);
			}
		}
		insertSchedule(performanceId, LocalDateTime.now().plusDays(20), "ON_SALE");
		return performanceId;
	}

	private void setSale(Long performanceId, LocalDateTime openAt, LocalDateTime closeAt) {
		jdbc.update("""
			UPDATE performance
			SET sale_open_at = ?, sale_close_at = ?, max_per_person = 4, receive_type = 'ONSITE', booking_fee = 1000
			WHERE id = ?
			""", Timestamp.valueOf(openAt), Timestamp.valueOf(closeAt), performanceId);
	}

	private Long insertGrade(Long performanceId, String name) {
		return jdbc.queryForObject(
			"INSERT INTO seat_grade (performance_id, name, price) VALUES (?, ?, 100000) RETURNING id",
			Long.class, performanceId, name);
	}

	private void setZone(Long performanceId, Long zoneId, String status, Long gradeId) {
		jdbc.update("UPDATE zone_grade SET assignment_status = ?, grade_id = ? WHERE performance_id = ? AND zone_id = ?",
			status, gradeId, performanceId, zoneId);
	}

	/** 공연장 구역 번호. 응답의 구역 순서(sortOrder, 같으면 번호순)와 같은 순서다. */
	private List<Long> zoneIds(Long performanceId) {
		return jdbc.queryForList("""
			SELECT z.id FROM venue_zone z
			WHERE z.venue_id = (SELECT venue_id FROM performance WHERE id = ?)
			ORDER BY z.sort_order, z.id
			""", Long.class, performanceId);
	}

	private void insertSchedule(Long performanceId, LocalDateTime startAt, String status) {
		jdbc.update("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, NOW(), NOW())
			""", performanceId, Timestamp.valueOf(startAt), status);
	}

	private String statusOf(Long performanceId) {
		return jdbc.queryForObject("SELECT status FROM performance WHERE id = ?", String.class, performanceId);
	}

	/** 막히면 코드와 상태로 응답하고 공연은 작성 중 그대로여야 한다. */
	private void assertRejected(Long performanceId, int httpStatus, String code) throws Exception {
		publish(performanceId)
			.andExpect(status().is(httpStatus))
			.andExpect(jsonPath("$.code").value(code));
		assertThat(statusOf(performanceId)).isEqualTo("DRAFT");
	}

	@Test
	@DisplayName("조건을 모두 만족하면 PUBLISHED 로 바뀌고 의도적 미판매 구역이 응답에 나오며, 담당자도 공개할 수 있다")
	void publish() throws Exception {
		Long performanceId = insertReadyPerformance();
		Long unsoldZone = zoneIds(performanceId).get(1);

		String response = publish(performanceId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.performanceId").value(performanceId))
			.andExpect(jsonPath("$.data.status").value("PUBLISHED"))
			.andReturn().getResponse().getContentAsString();

		assertThat(JsonPath.<List<Integer>>read(response, "$.data.intentionallyUnsoldZones[*].zoneId"))
			.containsExactly(unsoldZone.intValue());
		assertThat(JsonPath.<String>read(response, "$.data.intentionallyUnsoldZones[0].name")).isNotBlank();
		assertThat(statusOf(performanceId)).isEqualTo("PUBLISHED");
	}

	@Test
	@DisplayName("이미 공개·취소된 공연은 다른 조건과 상관없이 409 PERFORMANCE400 이다")
	void notDraft() throws Exception {
		Long published = insertPerformance(MY_BUSINESS, "PUBLISHED", defaultPolicyId());
		Long canceled = insertPerformance(MY_BUSINESS, "CANCELED", defaultPolicyId());

		publish(published).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERFORMANCE400"));
		publish(canceled).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERFORMANCE400"));
		assertThat(statusOf(published)).isEqualTo("PUBLISHED");
		assertThat(statusOf(canceled)).isEqualTo("CANCELED");
	}

	@Test
	@DisplayName("공개 조건(판매 필드·판매 기간·회차·구역·전석 섞임)을 하나라도 어기면 코드별로 막히고 공연은 작성 중 그대로다")
	void rejectsUnmetConditions() throws Exception {
		Long saleFields = insertReadyPerformance();
		jdbc.update("UPDATE performance SET max_per_person = NULL WHERE id = ?", saleFields);
		assertRejected(saleFields, 400, "PERFORMANCE401");

		Long closed = insertReadyPerformance();
		setSale(closed, LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(1));
		assertRejected(closed, 400, "PERFORMANCE405");

		Long noSchedule = insertReadyPerformance();
		jdbc.update("UPDATE performance_schedule SET status = 'CANCELED' WHERE performance_id = ?", noSchedule);
		assertRejected(noSchedule, 400, "PERFORMANCE402");

		Long scheduleBeforeOpen = insertReadyPerformance();
		insertSchedule(scheduleBeforeOpen, LocalDateTime.now().plusHours(12), "ON_SALE");
		assertRejected(scheduleBeforeOpen, 400, "PERFORMANCE407");

		Long closeAfterLast = insertReadyPerformance();
		setSale(closeAfterLast, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(21));
		assertRejected(closeAfterLast, 400, "PERFORMANCE408");

		Long unset = insertReadyPerformance();
		setZone(unset, zoneIds(unset).get(2), "UNSET", null);
		assertRejected(unset, 400, "PERFORMANCE403");

		Long noAssigned = insertReadyPerformance();
		for (Long zoneId : zoneIds(noAssigned)) {
			setZone(noAssigned, zoneId, "INTENTIONALLY_UNSOLD", null);
		}
		assertRejected(noAssigned, 400, "PERFORMANCE404");

		Long allMixed = insertReadyPerformance();
		setZone(allMixed, zoneIds(allMixed).get(0), "ASSIGNED", insertGrade(allMixed, "ALL"));
		assertRejected(allMixed, 409, "PERFORMANCE303");
	}

	@Test
	@DisplayName("판매 오픈까지 3시간 이하면 400 PERFORMANCE406(이미 지난 경우 포함)이고, 3시간 1분 뒤면 공개된다")
	void saleOpenLeadTime() throws Exception {
		Long opened = insertReadyPerformance();
		setSale(opened, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(10));
		assertRejected(opened, 400, "PERFORMANCE406");

		// 정확히 3시간 뒤: 요청이 처리되는 시점에는 3시간이 안 남은 것이므로 막힌다.
		Long exactly = insertReadyPerformance();
		setSale(exactly, LocalDateTime.now().plusHours(3), LocalDateTime.now().plusDays(10));
		assertRejected(exactly, 400, "PERFORMANCE406");

		Long enough = insertReadyPerformance();
		setSale(enough, LocalDateTime.now().plusHours(3).plusMinutes(1), LocalDateTime.now().plusDays(10));
		publish(enough).andExpect(status().isOk());
	}

	@Test
	@DisplayName("다른 사업체의 공연은 상태와 상관없이 없는 공연과 같은 404 PERFORMANCE000 이고 바뀌지 않는다")
	void othersAndMissingAreNotFound() throws Exception {
		Long others = insertPerformance(OTHER_BUSINESS, "DRAFT", defaultPolicyId());
		Long othersPublished = insertPerformance(OTHER_BUSINESS, "PUBLISHED", defaultPolicyId());

		publish(others).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		publish(othersPublished).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		publish(Long.MAX_VALUE).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		assertThat(statusOf(others)).isEqualTo("DRAFT");
	}

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanCall() throws Exception {
		Long performanceId = insertReadyPerformance();

		mockMvc.perform(post(URL, performanceId).header("Authorization", token(AccountType.USER, null, null)))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(URL, performanceId)).andExpect(status().isUnauthorized());
		assertThat(statusOf(performanceId)).isEqualTo("DRAFT");
	}

	// ---- 동시성 ----

	@Test
	@DisplayName("같은 공연을 동시에 두 번 공개하면 하나만 200 이고 나머지는 409 다")
	void concurrentPublish() throws Exception {
		Long performanceId = insertReadyPerformance();

		List<MvcResult> results = runConcurrently(2, i -> () -> publish(performanceId).andReturn());

		assertThat(results).filteredOn(result -> result.getResponse().getStatus() == 200).hasSize(1);
		// 나머지는 이미 공개됨(PERFORMANCE400)이거나 락을 1초 안에 못 잡은 것(COMMON004)이고 둘 다 409 다.
		assertThat(results).filteredOn(result -> result.getResponse().getStatus() != 200)
			.allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(409));
		assertThat(statusOf(performanceId)).isEqualTo("PUBLISHED");
	}

	@Test
	@DisplayName("다른 작업이 공연 락을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고 공개되지 않는다")
	void performanceLockHeldByOthers() throws Exception {
		Long performanceId = insertReadyPerformance();

		assertLockedByOthers("performance:" + performanceId, performanceId);
	}

	@Test
	@DisplayName("다른 작업이 그 공연의 취소 규정 락을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고, 기본 규정은 락을 잡지 않아 공개된다")
	void cancelPolicyLock() throws Exception {
		Long policyId = insertMyPolicy();
		Long mine = insertReadyPerformance();
		jdbc.update("UPDATE performance SET cancel_policy_id = ? WHERE id = ?", policyId, mine);
		assertLockedByOthers("cancel-policy:" + policyId, mine);

		Long usingDefault = insertReadyPerformance();
		RLock lock = redissonClient.getLock("tickting:lock:cancel-policy:" + defaultPolicyId());
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			publish(usingDefault).andExpect(status().isOk());
		} finally {
			lock.forceUnlock();
		}
	}

	private void assertLockedByOthers(String lockName, Long performanceId) throws Exception {
		RLock lock = redissonClient.getLock("tickting:lock:" + lockName);
		// 락은 잡은 스레드의 것이라, 요청을 보내는 테스트 스레드가 아닌 다른 스레드에서 잡아야 기다리게 된다.
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			publish(performanceId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMON004"));
		} finally {
			lock.forceUnlock();
		}
		assertThat(statusOf(performanceId)).isEqualTo("DRAFT");
	}
}
