package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
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
 * 판매자의 취소 규정 수정(SLR-22)과 목록·상세의 수정 가능 여부(editable).
 * 공연 공개(SLR-04)가 아직 없어서 규정을 쓰는 공연은 SQL 로 상태를 정해 넣는다.
 * 서비스 기본 규정(V4 기준 데이터)은 지우지 않고, 시드에 없는 사업체 번호로 만든 공연·규정·구간만 지운다.
 */
class SellerCancelPolicyUpdateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/cancel-policies/{cancelPolicyId}";
	private static final long MY_BUSINESS = 887L;
	private static final long OTHER_BUSINESS = 888L;

	@Autowired
	private RedissonClient redissonClient;

	@AfterEach
	void deleteInsertedRows() {
		jdbc.update("DELETE FROM performance WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
		jdbc.update("""
			DELETE FROM cancel_policy_rule
			WHERE policy_id IN (SELECT id FROM cancel_policy WHERE business_id IN (?, ?))
			""", MY_BUSINESS, OTHER_BUSINESS);
		jdbc.update("DELETE FROM cancel_policy WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
	}

	private Cookie token(AccountType type, String role, Long businessId) {
		return sessionCookie(new CustomUserDetails(1L, type, role, businessId));
	}

	private Cookie myStaffToken() {
		return token(AccountType.SELLER, "STAFF", MY_BUSINESS);
	}

	/** 대표 전용 기능이 아니라서 담당자 토큰으로 보낸다. */
	private ResultActions update(Object cancelPolicyId, Object body) throws Exception {
		return mockMvc.perform(put(URL, cancelPolicyId)
			.cookie(myStaffToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private static Map<String, Object> rule(int daysFrom, Integer daysTo, int feeRate, boolean cancelable) {
		// daysTo 가 null 인 칸도 본문에 넣어야 해서 Map.of 대신 HashMap 을 쓴다.
		Map<String, Object> rule = new HashMap<>();
		rule.put("daysFrom", daysFrom);
		rule.put("daysTo", daysTo);
		rule.put("feeRate", feeRate);
		rule.put("cancelable", cancelable);
		return rule;
	}

	private static Map<String, Object> body(String name, List<Map<String, Object>> rules) {
		Map<String, Object> body = new HashMap<>();
		body.put("name", name);
		body.put("rules", rules);
		return body;
	}

	private static Map<String, Object> validBody(String name) {
		return body(name, List.of(rule(0, null, 0, true)));
	}

	/** 기존 구간 두 개(0~6일 전 10%, 7일 전부터 0%)를 가진 규정 */
	private long insertPolicy(long businessId, String name) {
		long policyId = jdbc.queryForObject(
			"INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, ?, NOW()) RETURNING id",
			Long.class, businessId, name);
		jdbc.update("""
			INSERT INTO cancel_policy_rule (policy_id, days_from, days_to, fee_rate, cancelable)
			VALUES (?, 0, 6, 10, TRUE), (?, 7, NULL, 0, TRUE)
			""", policyId, policyId);
		return policyId;
	}

	private long arenaVenueId() {
		return jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
	}

	/** 규정을 쓰는 공연. 공개(SLR-04)가 없어서 상태를 직접 넣는다. */
	private void insertPerformance(long cancelPolicyId, String status) {
		jdbc.update("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '규정 테스트 공연', 'CONCERT', 12, 150, 'https://cdn.example.com/poster.jpg', ?, NOW(), NOW())
			""", MY_BUSINESS, arenaVenueId(), cancelPolicyId, status);
	}

	private List<Map<String, Object>> savedRules(long policyId) {
		return jdbc.queryForList("""
			SELECT days_from, days_to, fee_rate, cancelable FROM cancel_policy_rule
			WHERE policy_id = ? ORDER BY days_from
			""", policyId);
	}

	private String savedName(long policyId) {
		return jdbc.queryForObject("SELECT name FROM cancel_policy WHERE id = ?", String.class, policyId);
	}

	// ---- 정상 수정 ----

	// ---- 수정할 수 없음 ----

	@Test
	@DisplayName("공개(PUBLISHED)되었거나 취소(CANCELED)된 공연이 쓰는 규정은 409 PERFORMANCE103 이고 그대로 남는다")
	void policyUsedByPublishedOrCanceledIsLocked() throws Exception {
		long publishedPolicy = insertPolicy(MY_BUSINESS, "공개 공연 규정");
		insertPerformance(publishedPolicy, "PUBLISHED");
		long canceledPolicy = insertPolicy(MY_BUSINESS, "취소 공연 규정");
		insertPerformance(canceledPolicy, "DRAFT");
		insertPerformance(canceledPolicy, "CANCELED");

		for (long policyId : List.of(publishedPolicy, canceledPolicy)) {
			update(policyId, validBody("바꾼 이름"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PERFORMANCE103"));
			assertThat(savedRules(policyId)).hasSize(2);
		}
		assertThat(savedName(publishedPolicy)).isEqualTo("공개 공연 규정");
	}

	@Test
	@DisplayName("다른 사업체의 규정과 없는 규정은 같은 404 PERFORMANCE100 이다")
	void otherBusinessPolicyIsNotFound() throws Exception {
		long othersPolicy = insertPolicy(OTHER_BUSINESS, "남의 규정");

		update(othersPolicy, validBody("가져간 규정"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE100"));
		update(Long.MAX_VALUE, validBody("없는 규정"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE100"));
		assertThat(savedName(othersPolicy)).isEqualTo("남의 규정");
	}

	// ---- 목록·상세의 editable ----

	// ---- 동시성 ----

	@Test
	@DisplayName("같은 규정을 서로 다른 구간으로 동시에 고쳐도 모두 순서대로 처리되고, 남은 구간은 한 요청의 결과와 정확히 같다")
	void concurrentUpdatesDoNotMixRules() throws Exception {
		long policyId = insertPolicy(MY_BUSINESS, "동시 수정 규정");
		int threads = 6;

		// 요청 i 는 "0~i일 전, i+1일 전부터" 두 구간이라 요청마다 구간 경계가 다르다. 섞이면 구간이 셋 이상 남는다.
		List<MvcResult> results = runConcurrently(threads, i -> () ->
			update(policyId, body("동시 수정 규정", List.of(rule(0, i, 10, true), rule(i + 1, null, 0, true))))
				.andReturn());

		assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
		List<Map<String, Object>> rules = savedRules(policyId);
		assertThat(rules).hasSize(2);
		int boundary = (Integer) rules.get(0).get("days_to");
		assertThat(rules.get(1)).containsEntry("days_from", boundary + 1).containsEntry("days_to", null);
	}

	@Test
	@DisplayName("다른 작업(공연 공개 등)이 그 규정의 락(cancel-policy:{규정 번호})을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고 규정은 그대로다")
	void lockHeldByOthers() throws Exception {
		long policyId = insertPolicy(MY_BUSINESS, "락 대기 규정");
		RLock lock = redissonClient.getLock("tickting:lock:cancel-policy:" + policyId);
		// 락은 잡은 스레드의 것이라, 요청을 보내는 테스트 스레드가 아닌 다른 스레드에서 잡아야 기다리게 된다.
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			update(policyId, validBody("바뀐 이름"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMON004"));
		} finally {
			lock.forceUnlock();
		}
		assertThat(savedRules(policyId)).hasSize(2);
	}
}
