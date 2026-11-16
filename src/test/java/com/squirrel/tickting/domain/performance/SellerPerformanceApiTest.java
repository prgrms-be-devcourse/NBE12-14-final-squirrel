package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 판매자의 내 공연 목록·상세(SLR-05·06).
 * 시드 공연(사업체 1번)을 믿는 다른 테스트가 있어서 공연 테이블을 비우지 않는다. 이 테스트는 시드에 없는
 * 사업체 번호(MY_BUSINESS, OTHER_BUSINESS)로 공연을 넣고, 끝나면 그 사업체의 데이터만 지운다.
 * 계정 종류·역할별 경로 규칙은 SecurityConfigTest 가 확인하므로 여기서는 이 API 가 막혀 있는지만 본다.
 */
class SellerPerformanceApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances";
	private static final long MY_BUSINESS = 777L;
	private static final long OTHER_BUSINESS = 778L;
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 10, 0);

	private final List<Long> insertedVenueIds = new ArrayList<>();

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		jdbc.update("DELETE FROM performance_image WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
		for (Long id : insertedVenueIds) {
			jdbc.update("DELETE FROM venue WHERE id = ?", id);
		}
		insertedVenueIds.clear();
	}

	private String token(AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 내 사업체 대표 토큰으로 보내는 목록 요청 */
	private MockHttpServletRequestBuilder listRequest() {
		return get(URL).header("Authorization", token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private Long arenaVenueId() {
		return jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
	}

	private Long defaultCancelPolicyId() {
		return jdbc.queryForObject("SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1",
			Long.class);
	}

	private Long insertVenue(String name, String status) {
		Long id = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES (?, '주소', '테스트지역', 100, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, name, status);
		insertedVenueIds.add(id);
		return id;
	}

	private Long insertPerformance(long businessId, Long venueId, String title, String status, LocalDateTime createdAt) {
		Timestamp time = Timestamp.valueOf(createdAt);
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, cast_info, poster_url, notice, guide, status, sale_open_at,
			                         sale_close_at, max_per_person, receive_type, booking_fee, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?, 'CONCERT', 12, 150, '가상 밴드', 'https://cdn.example.com/poster.jpg', '공지', '안내',
			        ?, '2026-11-25 20:00:00', '2026-12-20 18:00:00', 4, 'BOTH', 2000, ?, ?)
			RETURNING id
			""", Long.class, businessId, venueId, defaultCancelPolicyId(), title, status, time, time);
	}

	private Long insertPerformance(long businessId, String title, String status, LocalDateTime createdAt) {
		return insertPerformance(businessId, arenaVenueId(), title, status, createdAt);
	}

	private void insertSchedule(Long performanceId, LocalDateTime startAt, String status) {
		insertSchedule(performanceId, startAt, status, null);
	}

	private void insertSchedule(Long performanceId, LocalDateTime startAt, String status,
		LocalDateTime seatsGeneratedAt) {
		jdbc.update("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at,
			                                  updated_at)
			VALUES (?, ?, ?, ?, NOW(), NOW())
			""", performanceId, Timestamp.valueOf(startAt), status,
			seatsGeneratedAt == null ? null : Timestamp.valueOf(seatsGeneratedAt));
	}

	private void insertImage(Long performanceId, String url, int sortOrder) {
		jdbc.update("INSERT INTO performance_image (performance_id, url, sort_order) VALUES (?, ?, ?)",
			performanceId, url, sortOrder);
	}

	/** 내 사업체 대표 토큰으로 보내는 상세 요청 */
	private MockHttpServletRequestBuilder detailRequest(Long performanceId) {
		return get(URL + "/" + performanceId).header("Authorization", token(AccountType.SELLER, "OWNER", MY_BUSINESS));
	}

	private List<String> titles(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].title");
	}

	private List<String> titlesOf(MockHttpServletRequestBuilder request) throws Exception {
		return titles(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
	}

	@Test
	@DisplayName("예매자 토큰은 목록·상세 모두 403, 토큰이 없으면 401이다")
	void onlySellerCanCall() throws Exception {
		Long id = insertPerformance(MY_BUSINESS, "내 공연", "PUBLISHED", BASE);
		String userToken = token(AccountType.USER, null, null);

		mockMvc.perform(get(URL).header("Authorization", userToken)).andExpect(status().isForbidden());
		mockMvc.perform(get(URL + "/" + id).header("Authorization", userToken)).andExpect(status().isForbidden());
		mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("목록에는 내 사업체 공연만 나오고, 공연장명과 회차 수(취소 회차 포함, 회차 없으면 0)가 함께 나온다")
	void listShowsOnlyMyPerformances() throws Exception {
		Long withSchedules = insertPerformance(MY_BUSINESS, "회차 있는 공연", "PUBLISHED", BASE.plusDays(1));
		insertSchedule(withSchedules, BASE.plusDays(30), "ON_SALE");
		insertSchedule(withSchedules, BASE.plusDays(31), "PAUSED");
		insertSchedule(withSchedules, BASE.plusDays(32), "CANCELED");
		insertPerformance(MY_BUSINESS, "회차 없는 공연", "DRAFT", BASE);
		insertPerformance(OTHER_BUSINESS, "남의 공연", "PUBLISHED", BASE.plusDays(2));

		MvcResult result = mockMvc.perform(listRequest()).andExpect(status().isOk()).andReturn();
		String body = result.getResponse().getContentAsString();
		Map<String, Object> first = JsonPath.read(body, "$.data.content[0]");

		assertThat(titles(result)).containsExactly("회차 있는 공연", "회차 없는 공연");
		assertThat(first.keySet()).containsExactlyInAnyOrder("performanceId", "title", "status", "posterUrl",
			"venueName", "scheduleCount", "createdAt");
		assertThat(first.get("venueName")).isEqualTo("가상 아레나 (샘플)");
		assertThat(first.get("scheduleCount")).isEqualTo(3);
		assertThat(JsonPath.<Integer>read(body, "$.data.content[1].scheduleCount")).isZero();
	}

	@Test
	@DisplayName("최신 등록이 먼저 나오고, 등록 시각이 같으면 번호가 큰 공연이 먼저 나오며, 정렬(sort) 파라미터는 무시한다")
	void sortedByNewestFirst() throws Exception {
		insertPerformance(MY_BUSINESS, "오래된 공연", "PUBLISHED", BASE);
		insertPerformance(MY_BUSINESS, "같은 시각 먼저 등록", "PUBLISHED", BASE.plusDays(1));
		insertPerformance(MY_BUSINESS, "같은 시각 나중 등록", "PUBLISHED", BASE.plusDays(1));

		assertThat(titlesOf(listRequest().param("sort", "title,asc")))
			.containsExactly("같은 시각 나중 등록", "같은 시각 먼저 등록", "오래된 공연");
	}

	@Test
	@DisplayName("상태를 주지 않으면 취소된 공연까지 모두 나오고, 상태를 주면 그 상태만 나온다")
	void filterByStatus() throws Exception {
		insertPerformance(MY_BUSINESS, "작성 중", "DRAFT", BASE);
		insertPerformance(MY_BUSINESS, "공개", "PUBLISHED", BASE.plusDays(1));
		insertPerformance(MY_BUSINESS, "취소", "CANCELED", BASE.plusDays(2));

		assertThat(titlesOf(listRequest())).containsExactly("취소", "공개", "작성 중");
		assertThat(titlesOf(listRequest().param("status", "CANCELED"))).containsExactly("취소");
		assertThat(titlesOf(listRequest().param("status", "DRAFT"))).containsExactly("작성 중");
	}

	@Test
	@DisplayName("사용 중지된 공연장에 잡힌 공연도 공연장명이 보인다")
	void inactiveVenueNameIsShown() throws Exception {
		Long inactiveVenue = insertVenue("사용 중지 공연장", "INACTIVE");
		insertPerformance(MY_BUSINESS, inactiveVenue, "중지 공연장 공연", "PUBLISHED", BASE);

		mockMvc.perform(listRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].venueName").value("사용 중지 공연장"));
	}

	@Test
	@DisplayName("크기를 주지 않으면 20개씩 나오고 21개면 2페이지에 1개가 남으며, 그 다음 페이지는 빈 목록이다")
	void defaultPageSizeIsTwenty() throws Exception {
		for (int i = 0; i < 21; i++) {
			insertPerformance(MY_BUSINESS, "공연" + String.format("%02d", i), "PUBLISHED", BASE.plusMinutes(i));
		}

		mockMvc.perform(listRequest())
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(21))
			.andExpect(jsonPath("$.data.totalPages").value(2));
		mockMvc.perform(listRequest().param("page", "1"))
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].title").value("공연00"));
		mockMvc.perform(listRequest().param("page", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(0));
	}

	@Test
	@DisplayName("크기는 최대 20개로 줄어들고, 크기 0은 에러(500)가 아니라 기본값으로 처리된다")
	void pageSizeIsCappedAtTwenty() throws Exception {
		for (int i = 0; i < 21; i++) {
			insertPerformance(MY_BUSINESS, "공연" + i, "PUBLISHED", BASE.plusMinutes(i));
		}

		mockMvc.perform(listRequest().param("size", "21"))
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.size").value(20));
		mockMvc.perform(listRequest().param("size", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(20));
	}

	// ---------------------------------------------------------------- 상세

	@Test
	@DisplayName("상세에는 공연 정보와 공연장, 이미지(순서대로), 회차(공연 일시순, 취소 회차 포함)가 나온다")
	void detailShowsEverything() throws Exception {
		Long id = insertPerformance(MY_BUSINESS, "가상 콘서트", "PUBLISHED", BASE);
		insertImage(id, "https://cdn.example.com/detail-2.jpg", 2);
		insertImage(id, "https://cdn.example.com/detail-1.jpg", 1);
		insertSchedule(id, LocalDateTime.of(2026, 12, 21, 19, 0), "CANCELED");
		insertSchedule(id, LocalDateTime.of(2026, 12, 20, 19, 0), "ON_SALE", LocalDateTime.of(2026, 11, 24, 20, 0));

		mockMvc.perform(detailRequest(id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.performanceId").value(id))
			.andExpect(jsonPath("$.data.status").value("PUBLISHED"))
			.andExpect(jsonPath("$.data.venue.venueId").value(arenaVenueId()))
			.andExpect(jsonPath("$.data.venue.name").value("가상 아레나 (샘플)"))
			.andExpect(jsonPath("$.data.title").value("가상 콘서트"))
			.andExpect(jsonPath("$.data.genre").value("CONCERT"))
			.andExpect(jsonPath("$.data.ageLimit").value(12))
			.andExpect(jsonPath("$.data.runningTime").value(150))
			.andExpect(jsonPath("$.data.cancelPolicyId").value(defaultCancelPolicyId()))
			.andExpect(jsonPath("$.data.saleOpenAt").value("2026-11-25T20:00:00"))
			.andExpect(jsonPath("$.data.maxPerPerson").value(4))
			.andExpect(jsonPath("$.data.receiveType").value("BOTH"))
			.andExpect(jsonPath("$.data.bookingFee").value(2000))
			.andExpect(jsonPath("$.data.images[0].sortOrder").value(1))
			.andExpect(jsonPath("$.data.images[0].url").value("https://cdn.example.com/detail-1.jpg"))
			.andExpect(jsonPath("$.data.images[1].sortOrder").value(2))
			.andExpect(jsonPath("$.data.schedules.length()").value(2))
			.andExpect(jsonPath("$.data.schedules[0].startAt").value("2026-12-20T19:00:00"))
			.andExpect(jsonPath("$.data.schedules[0].status").value("ON_SALE"))
			.andExpect(jsonPath("$.data.schedules[0].seatsGeneratedAt").value("2026-11-24T20:00:00"))
			.andExpect(jsonPath("$.data.schedules[1].status").value("CANCELED"))
			.andExpect(jsonPath("$.data.schedules[1].seatsGeneratedAt").value((Object) null));
	}

	@Test
	@DisplayName("작성 중인 공연은 판매 필드가 null이고, 이미지·회차가 없으면 빈 배열이다")
	void draftDetailHasNullSaleFields() throws Exception {
		Long id = insertPerformance(MY_BUSINESS, "작성 중", "DRAFT", BASE);
		jdbc.update("""
			UPDATE performance
			SET sale_open_at = NULL, sale_close_at = NULL, max_per_person = NULL, receive_type = NULL,
			    booking_fee = NULL
			WHERE id = ?
			""", id);

		mockMvc.perform(detailRequest(id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("DRAFT"))
			.andExpect(jsonPath("$.data.saleOpenAt").value((Object) null))
			.andExpect(jsonPath("$.data.saleCloseAt").value((Object) null))
			.andExpect(jsonPath("$.data.maxPerPerson").value((Object) null))
			.andExpect(jsonPath("$.data.receiveType").value((Object) null))
			.andExpect(jsonPath("$.data.bookingFee").value((Object) null))
			.andExpect(jsonPath("$.data.images.length()").value(0))
			.andExpect(jsonPath("$.data.schedules.length()").value(0));
	}

	@Test
	@DisplayName("다른 사업체의 공연과 없는 공연은 똑같이 404 (PERFORMANCE000)이다")
	void othersAndMissingPerformanceAreNotFound() throws Exception {
		Long othersId = insertPerformance(OTHER_BUSINESS, "남의 공연", "PUBLISHED", BASE);

		mockMvc.perform(detailRequest(othersId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		mockMvc.perform(detailRequest(othersId + 1000))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
	}
}
