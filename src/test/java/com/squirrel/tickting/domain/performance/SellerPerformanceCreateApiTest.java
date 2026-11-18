package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 공연 등록(SLR-02).
 * 시드 공연·공연장을 믿는 다른 테스트가 있어서 공연·공연장 테이블을 비우지 않는다. 이 테스트는 시드에 없는
 * 사업체 번호(MY_BUSINESS, OTHER_BUSINESS)와 직접 넣은 공연장을 쓰고, 끝나면 그 데이터만 지운다.
 * 계정 종류·역할별 경로 규칙은 SecurityConfigTest 가 확인하므로 여기서는 이 API 가 막혀 있는지만 본다.
 */
class SellerPerformanceCreateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances";
	private static final long MY_BUSINESS = 791L;
	private static final long OTHER_BUSINESS = 792L;
	private static final long MY_ACCOUNT = 31L;

	private final List<Long> insertedVenueIds = new ArrayList<>();

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM zone_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_image WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
		jdbc.update("DELETE FROM cancel_policy WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
		for (Long id : insertedVenueIds) {
			jdbc.update("DELETE FROM venue_zone WHERE venue_id = ?", id);
			jdbc.update("DELETE FROM venue WHERE id = ?", id);
		}
		insertedVenueIds.clear();
	}

	// ---- 데이터 준비 ----

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(MY_ACCOUNT, type, role, businessId));
	}

	private String staffToken() {
		return token(AccountType.SELLER, "STAFF", MY_BUSINESS);
	}

	private Long insertVenue(String status, int zoneCount) {
		Long venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('등록 테스트 공연장', '주소', '테스트지역', 100, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, status);
		insertedVenueIds.add(venueId);
		for (int i = 1; i <= zoneCount; i++) {
			jdbc.update("INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, ?, ?)", venueId, i + "구역", i);
		}
		return venueId;
	}

	private Long insertPolicy(long businessId) {
		return jdbc.queryForObject("""
			INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, '사업체 규정', NOW()) RETURNING id
			""", Long.class, businessId);
	}

	private Long defaultPolicyId() {
		return jdbc.queryForObject("SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1",
			Long.class);
	}

	/** 필수 값만 채운 본문. 판매 필드와 선택 입력은 비워 둔다. */
	private Map<String, Object> requiredBody(Long venueId, Long cancelPolicyId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("venueId", venueId);
		body.put("cancelPolicyId", cancelPolicyId);
		body.put("title", "가상 콘서트");
		body.put("genre", "CONCERT");
		body.put("ageLimit", 12);
		body.put("runningTime", 150);
		body.put("posterUrl", "https://cdn.example.com/posters/12.jpg");
		return body;
	}

	private ResultActions register(String token, Map<String, Object> body) throws Exception {
		return mockMvc.perform(post(URL)
			.header("Authorization", token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private long registeredId(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.<Number>read(body, "$.data.performanceId").longValue();
	}

	private void expectBadRequest(Map<String, Object> body, String code) throws Exception {
		register(staffToken(), body)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(code));
	}

	private int countPerformances() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM performance WHERE business_id = ?", Integer.class,
			MY_BUSINESS);
	}

	// ---- 정상 등록 ----

	@Test
	@DisplayName("모든 값을 넣으면 DRAFT 로 저장되고, 사업체·등록자는 토큰 값이며, 이미지와 구역별 UNSET 설정이 함께 생긴다")
	void registerWithAllFields() throws Exception {
		Long venueId = insertVenue("ACTIVE", 3);
		Map<String, Object> body = requiredBody(venueId, insertPolicy(MY_BUSINESS));
		body.put("castInfo", "가상 밴드");
		body.put("notice", "공연 시작 30분 전부터 입장 가능합니다.");
		body.put("guide", "촬영·녹음은 금지됩니다.");
		body.put("saleOpenAt", "2026-12-01T10:00:00");
		body.put("saleCloseAt", "2026-12-20T18:00:00");
		body.put("maxPerPerson", 4);
		body.put("receiveType", "ONSITE");
		body.put("bookingFee", 2000);
		body.put("images", List.of(
			Map.of("url", "https://cdn.example.com/details/12-2.jpg", "sortOrder", 2),
			Map.of("url", "https://cdn.example.com/details/12-1.jpg", "sortOrder", 1)));

		ResultActions result = register(token(AccountType.SELLER, "OWNER", MY_BUSINESS), body)
			.andExpect(jsonPath("$.data.status").value("DRAFT"))
			.andExpect(jsonPath("$.data.venueId").value(venueId))
			.andExpect(jsonPath("$.data.createdAt").exists());
		long id = registeredId(result);

		Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM performance WHERE id = ?", id);
		assertThat(saved.get("business_id")).isEqualTo(MY_BUSINESS);
		assertThat(saved.get("created_by")).isEqualTo(MY_ACCOUNT);
		assertThat(saved.get("status")).isEqualTo("DRAFT");
		assertThat(saved.get("max_per_person")).isEqualTo(4);
		assertThat(saved.get("receive_type")).isEqualTo("ONSITE");

		assertThat(jdbc.queryForList(
			"SELECT url FROM performance_image WHERE performance_id = ? ORDER BY sort_order", String.class, id))
			.containsExactly("https://cdn.example.com/details/12-1.jpg", "https://cdn.example.com/details/12-2.jpg");

		List<Long> zoneIds = jdbc.queryForList("SELECT id FROM venue_zone WHERE venue_id = ?", Long.class, venueId);
		assertThat(jdbc.queryForList(
			"SELECT zone_id FROM zone_grade WHERE performance_id = ? AND assignment_status = 'UNSET' AND grade_id IS NULL",
			Long.class, id)).containsExactlyInAnyOrderElementsOf(zoneIds);
	}

	@Test
	@DisplayName("판매 필드와 이미지를 모두 비워도 등록되고, 담당자 토큰과 서비스 기본 규정으로도 등록된다")
	void registerWithOnlyRequiredFields() throws Exception {
		long id = registeredId(register(staffToken(), requiredBody(insertVenue("ACTIVE", 2), defaultPolicyId())));

		Map<String, Object> saved = jdbc.queryForMap("""
			SELECT sale_open_at, sale_close_at, max_per_person, receive_type, booking_fee, cancel_policy_id
			FROM performance WHERE id = ?
			""", id);
		assertThat(saved.get("cancel_policy_id")).isEqualTo(defaultPolicyId());
		assertThat(saved).containsEntry("sale_open_at", null).containsEntry("sale_close_at", null)
			.containsEntry("max_per_person", null).containsEntry("receive_type", null).containsEntry("booking_fee", null);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_image WHERE performance_id = ?",
			Integer.class, id)).isZero();
	}

	@Test
	@DisplayName("구역이 없는 공연장도 등록은 된다 (공개할 때 막힌다)")
	void registerOnVenueWithoutZones() throws Exception {
		long id = registeredId(register(staffToken(), requiredBody(insertVenue("ACTIVE", 0), defaultPolicyId())));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM zone_grade WHERE performance_id = ?", Integer.class, id))
			.isZero();
	}

	@Test
	@DisplayName("문자열은 앞뒤 공백을 지우고, 선택 입력이 공백뿐이면 비운(null) 것으로 저장한다")
	void trimsAndBlankToNull() throws Exception {
		Map<String, Object> body = requiredBody(insertVenue("ACTIVE", 1), defaultPolicyId());
		body.put("title", "  가상 콘서트  ");
		body.put("posterUrl", " https://cdn.example.com/posters/12.jpg ");
		body.put("castInfo", "   ");
		body.put("notice", "");
		body.put("guide", "  줄바꿈\n허용  ");
		body.put("images", List.of(Map.of("url", " https://cdn.example.com/details/1.jpg ", "sortOrder", 0)));

		long id = registeredId(register(staffToken(), body));

		Map<String, Object> saved = jdbc.queryForMap(
			"SELECT title, poster_url, cast_info, notice, guide FROM performance WHERE id = ?", id);
		assertThat(saved.get("title")).isEqualTo("가상 콘서트");
		assertThat(saved.get("poster_url")).isEqualTo("https://cdn.example.com/posters/12.jpg");
		assertThat(saved.get("cast_info")).isNull();
		assertThat(saved.get("notice")).isNull();
		assertThat(saved.get("guide")).isEqualTo("줄바꿈\n허용");
		assertThat(jdbc.queryForObject("SELECT url FROM performance_image WHERE performance_id = ?", String.class, id))
			.isEqualTo("https://cdn.example.com/details/1.jpg");
	}

	// ---- 권한 ----

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이고 공연이 만들어지지 않는다")
	void onlySellerCanRegister() throws Exception {
		Map<String, Object> body = requiredBody(insertVenue("ACTIVE", 1), defaultPolicyId());

		register(token(AccountType.USER, null, null), body).andExpect(status().isForbidden());
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isUnauthorized());
		assertThat(countPerformances()).isZero();
	}

	// ---- 공연장·취소 규정 확인 ----

	@Test
	@DisplayName("다른 사업체의 취소 규정과 없는 규정은 같은 404 PERFORMANCE100 이다")
	void otherBusinessPolicyIsNotFound() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);

		register(staffToken(), requiredBody(venueId, insertPolicy(OTHER_BUSINESS)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE100"));
		register(staffToken(), requiredBody(venueId, 999_999L))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE100"));
		assertThat(countPerformances()).isZero();
	}

	@Test
	@DisplayName("없는 공연장은 404 VENUE000, 사용 중지된 공연장은 400 VENUE001 이다")
	void venueMustExistAndBeActive() throws Exception {
		register(staffToken(), requiredBody(999_999L, defaultPolicyId()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("VENUE000"));
		expectBadRequest(requiredBody(insertVenue("INACTIVE", 1), defaultPolicyId()), "VENUE001");
		assertThat(countPerformances()).isZero();
	}

	// ---- 입력 검증 ----

	@Test
	@DisplayName("필수 값이 없거나 공백뿐이면 400 COMMON000 이다")
	void requiredFieldsMissing() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);
		for (String field : List.of("venueId", "cancelPolicyId", "title", "genre", "ageLimit", "runningTime",
			"posterUrl")) {
			Map<String, Object> body = requiredBody(venueId, defaultPolicyId());
			body.remove(field);
			expectBadRequest(body, "COMMON000");
		}

		Map<String, Object> blankTitle = requiredBody(venueId, defaultPolicyId());
		blankTitle.put("title", "   ");
		expectBadRequest(blankTitle, "COMMON000");
		assertThat(countPerformances()).isZero();
	}

	@Test
	@DisplayName("관람 연령·러닝타임·1인 최대 매수·예매 수수료가 범위를 벗어나면 400 이고, 경계값은 통과한다")
	void numberRanges() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);
		List<Object[]> invalid = List.of(
			new Object[] {"ageLimit", -1}, new Object[] {"ageLimit", 101},
			new Object[] {"runningTime", 0}, new Object[] {"runningTime", 1441},
			new Object[] {"maxPerPerson", 0}, new Object[] {"maxPerPerson", 6},
			new Object[] {"bookingFee", -1}, new Object[] {"bookingFee", 10001});
		for (Object[] field : invalid) {
			Map<String, Object> body = requiredBody(venueId, defaultPolicyId());
			body.put((String)field[0], field[1]);
			expectBadRequest(body, "COMMON000");
		}

		Map<String, Object> edges = requiredBody(venueId, defaultPolicyId());
		edges.put("ageLimit", 0);
		edges.put("runningTime", 1440);
		edges.put("maxPerPerson", 5);
		edges.put("bookingFee", 0);
		register(staffToken(), edges).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("포스터·이미지 주소가 http(s) 가 아니거나 공백을 품거나 2048자를 넘으면 400 이다")
	void urlFormat() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);
		for (String url : List.of("ftp://cdn.example.com/a.jpg", "javascript:alert(1)", "https://cdn.example.com/a b.jpg",
			"https://" + "a".repeat(2041))) {
			Map<String, Object> poster = requiredBody(venueId, defaultPolicyId());
			poster.put("posterUrl", url);
			expectBadRequest(poster, "COMMON000");

			Map<String, Object> image = requiredBody(venueId, defaultPolicyId());
			image.put("images", List.of(Map.of("url", url, "sortOrder", 1)));
			expectBadRequest(image, "COMMON000");
		}
		assertThat(countPerformances()).isZero();
	}

	@Test
	@DisplayName("포스터·이미지 주소는 우리 저장소 주소만 받는다 (http 와 다른 사이트 주소는 400)")
	void onlyOurImageUrlAccepted() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);
		for (String url : List.of("http://cdn.example.com/posters/12.jpg", "https://other.example.com/posters/12.jpg",
			"https://cdn.example.com.evil.com/posters/12.jpg")) {
			Map<String, Object> poster = requiredBody(venueId, defaultPolicyId());
			poster.put("posterUrl", url);
			expectBadRequest(poster, "COMMON000");

			Map<String, Object> image = requiredBody(venueId, defaultPolicyId());
			image.put("images", List.of(Map.of("url", url, "sortOrder", 1)));
			expectBadRequest(image, "COMMON000");
		}
		assertThat(countPerformances()).isZero();

		Map<String, Object> ok = requiredBody(venueId, defaultPolicyId());
		ok.put("posterUrl", "https://cdn.example.com/public/poster/seller-31/3f2a.jpg");
		ok.put("images", List.of(Map.of("url", "https://cdn.example.com/public/detail/seller-31/8c1d.jpg", "sortOrder", 1)));
		register(staffToken(), ok).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("공연명이 200자를 넘으면 400 이고 200자는 통과한다")
	void titleLength() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);
		Map<String, Object> tooLong = requiredBody(venueId, defaultPolicyId());
		tooLong.put("title", "가".repeat(201));
		expectBadRequest(tooLong, "COMMON000");

		Map<String, Object> max = requiredBody(venueId, defaultPolicyId());
		max.put("title", "가".repeat(200));
		register(staffToken(), max).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("판매 종료가 오픈보다 늦지 않으면(같은 시각 포함) 400 PERFORMANCE001, 한쪽만 넣으면 통과한다")
	void salePeriod() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);
		for (String closeAt : List.of("2026-12-01T09:59:00", "2026-12-01T10:00:00")) {
			Map<String, Object> body = requiredBody(venueId, defaultPolicyId());
			body.put("saleOpenAt", "2026-12-01T10:00:00");
			body.put("saleCloseAt", closeAt);
			expectBadRequest(body, "PERFORMANCE001");
		}
		assertThat(countPerformances()).isZero();

		Map<String, Object> onlyOpen = requiredBody(venueId, defaultPolicyId());
		onlyOpen.put("saleOpenAt", "2026-12-01T10:00:00");
		register(staffToken(), onlyOpen).andExpect(status().isCreated());
	}

	@Test
	@DisplayName("이미지 순서값이 겹치거나, 비었거나, 음수이거나, 이미지 항목이 null 이면 400 이다")
	void imageOrders() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);
		String url = "https://cdn.example.com/details/1.jpg";
		Map<String, Object> noOrder = new LinkedHashMap<>();
		noOrder.put("url", url);
		List<Object> nullItem = new ArrayList<>();
		nullItem.add(null);

		for (List<?> images : List.of(
			List.of(Map.of("url", url, "sortOrder", 1), Map.of("url", url, "sortOrder", 1)),
			List.of(noOrder),
			List.of(Map.of("url", url, "sortOrder", -1)),
			nullItem)) {
			Map<String, Object> body = requiredBody(venueId, defaultPolicyId());
			body.put("images", images);
			expectBadRequest(body, "COMMON000");
		}
		assertThat(countPerformances()).isZero();
	}

	@Test
	@DisplayName("장르·수령 방법이 정해진 값이 아니거나(소문자, 빈 문자열 포함) 숫자 칸에 글자가 오면 400 COMMON000 이다")
	void invalidEnumsAndTypes() throws Exception {
		Long venueId = insertVenue("ACTIVE", 1);
		List<Object[]> invalid = List.of(
			new Object[] {"genre", "concert"}, new Object[] {"genre", "OPERA"}, new Object[] {"genre", ""},
			new Object[] {"receiveType", "PICKUP"}, new Object[] {"ageLimit", "열두"});
		for (Object[] field : invalid) {
			Map<String, Object> body = requiredBody(venueId, defaultPolicyId());
			body.put((String)field[0], field[1]);
			expectBadRequest(body, "COMMON000");
		}
		assertThat(countPerformances()).isZero();
	}
}
