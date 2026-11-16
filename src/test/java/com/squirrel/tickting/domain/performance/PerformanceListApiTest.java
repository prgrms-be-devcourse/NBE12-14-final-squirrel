package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공연 목록. 공연장·공연·회차·좌석을 SQL 로 직접 넣고 확인한다. (판매자 공연 등록 API 는 다른 파트 작업이라 기대지 않는다)
 * 시각은 DB 의 NOW() 가 아니라 자바에서 만든 값을 넣는다. 컨테이너 DB 와 애플리케이션의 시간대가 달라도 어긋나지 않게 하려는 것이다.
 * MockMvc 는 테스트와 같은 스레드에서 돌아서, 요청도 이 테스트의 트랜잭션 안에서 실행되고 넣은 데이터를 읽는다.
 */
@Transactional
class PerformanceListApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/performances";

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private Long venueId;
	private Long cancelPolicyId;
	private long venueSeatSequence;

	/**
	 * 테스트 DB 에는 로컬 시드 공연이 들어 있고 SeedDataTest 가 그 값을 확인한다. 그래서 지우지 않고,
	 * 이 테스트의 트랜잭션 안에서만 시드 공연을 목록에서 숨긴다(DRAFT). 테스트가 끝나면 넣은 데이터와 함께 되돌려진다.
	 */
	@BeforeEach
	void setUpPerformanceTables() {
		jdbc.update("UPDATE performance SET status = 'DRAFT'");
		cancelPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		venueId = saveVenue("가상 아레나", "서울");
	}

	// ---- 데이터 만들기 ----

	private Long saveVenue(String name, String region) {
		return jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES (?, '주소', ?, 100, 'ACTIVE', ?, ?) RETURNING id
			""", Long.class, name, region, now, now);
	}

	/** 판매 오픈은 어제, 판매 종료는 30일 뒤인 공연. */
	private Long savePerformance(String title, String genre, String status) {
		return savePerformance(title, genre, status, now.minusDays(1), now);
	}

	private Long savePerformance(String title, String genre, String status, LocalDateTime saleOpenAt,
		LocalDateTime createdAt) {
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, sale_open_at, sale_close_at, max_per_person,
			                         receive_type, booking_fee, created_at, updated_at)
			VALUES (1, ?, 1, ?, ?, ?, 12, 120, 'https://cdn.example.com/p.png', ?, ?, ?, 4, 'ONSITE', 2000, ?, ?)
			RETURNING id
			""", Long.class, venueId, cancelPolicyId, title, genre, status, saleOpenAt, now.plusDays(30),
			createdAt, createdAt);
	}

	private Long saveSchedule(Long performanceId, LocalDateTime startAt, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?) RETURNING id
			""", Long.class, performanceId, startAt, status, now, now);
	}

	/** 한 회차 안에서 같은 공연장 좌석이 두 번 나올 수 없어서(UNIQUE) 좌석 번호를 매번 바꾼다. */
	private void saveSeat(Long scheduleId, String status) {
		jdbc.update("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, status, version, updated_at)
			VALUES (?, ?, 1, ?, 0, ?)
			""", scheduleId, ++venueSeatSequence, status, now);
	}

	/** 판매 중인 공연: 회차 하나와 판매 가능 좌석 하나. */
	private Long saveOnSale(String title, String genre, LocalDateTime startAt) {
		Long id = savePerformance(title, genre, "PUBLISHED");
		saveSeat(saveSchedule(id, startAt, "ON_SALE"), "AVAILABLE");
		return id;
	}

	private List<String> titles(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].title");
	}

	private MvcResult getList(String query) throws Exception {
		return mockMvc.perform(get(URL + query)).andExpect(status().isOk()).andReturn();
	}

	// ---- 테스트 ----

	@Test
	@DisplayName("로그인 없이 공연 목록을 보고, 공연장 이름·지역과 첫·마지막 회차 일시가 함께 온다")
	void listWithoutLogin() throws Exception {
		Long id = savePerformance("가상 콘서트", "CONCERT", "PUBLISHED");
		Long first = saveSchedule(id, now.plusDays(3), "ON_SALE");
		saveSchedule(id, now.plusDays(4), "ON_SALE");
		saveSeat(first, "AVAILABLE");

		mockMvc.perform(get(URL))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].performanceId").value(id))
			.andExpect(jsonPath("$.data.content[0].title").value("가상 콘서트"))
			.andExpect(jsonPath("$.data.content[0].genre").value("CONCERT"))
			.andExpect(jsonPath("$.data.content[0].posterUrl").value("https://cdn.example.com/p.png"))
			.andExpect(jsonPath("$.data.content[0].ageLimit").value(12))
			.andExpect(jsonPath("$.data.content[0].venueName").value("가상 아레나"))
			.andExpect(jsonPath("$.data.content[0].region").value("서울"))
			.andExpect(jsonPath("$.data.content[0].firstStartAt").value(now.plusDays(3).toString()))
			.andExpect(jsonPath("$.data.content[0].lastStartAt").value(now.plusDays(4).toString()))
			.andExpect(jsonPath("$.data.content[0].saleOpenAt").value(now.minusDays(1).toString()))
			.andExpect(jsonPath("$.data.content[0].saleStatus").value("ON_SALE"));
	}

	@Test
	@DisplayName("공연장이 다른 공연은 각자 자기 공연장의 이름·지역이 붙는다")
	void eachPerformanceGetsItsOwnVenue() throws Exception {
		saveOnSale("서울 공연", "CONCERT", now.plusDays(1));
		venueId = saveVenue("부산 홀", "부산");
		saveOnSale("부산 공연", "CONCERT", now.plusDays(2));

		mockMvc.perform(get(URL))
			.andExpect(jsonPath("$.data.content[0].title").value("서울 공연"))
			.andExpect(jsonPath("$.data.content[0].venueName").value("가상 아레나"))
			.andExpect(jsonPath("$.data.content[0].region").value("서울"))
			.andExpect(jsonPath("$.data.content[1].title").value("부산 공연"))
			.andExpect(jsonPath("$.data.content[1].venueName").value("부산 홀"))
			.andExpect(jsonPath("$.data.content[1].region").value("부산"));
	}

	@Test
	@DisplayName("작성 중(DRAFT)·취소(CANCELED) 공연은 목록에 보이지 않는다")
	void onlyPublished() throws Exception {
		saveOnSale("공개 공연", "CONCERT", now.plusDays(3));
		savePerformance("작성 중 공연", "CONCERT", "DRAFT");
		savePerformance("취소된 공연", "CONCERT", "CANCELED");

		assertThat(titles(getList(""))).containsExactly("공개 공연");
	}

	@Test
	@DisplayName("장르를 주지 않으면 전체, 주면 그 장르만 나온다")
	void filterByGenre() throws Exception {
		saveOnSale("콘서트", "CONCERT", now.plusDays(1));
		saveOnSale("뮤지컬", "MUSICAL", now.plusDays(2));
		saveOnSale("연극", "PLAY", now.plusDays(3));

		assertThat(titles(getList(""))).containsExactly("콘서트", "뮤지컬", "연극");
		assertThat(titles(getList("?genre=MUSICAL"))).containsExactly("뮤지컬");
		assertThat(titles(getList("?genre=CLASSIC"))).isEmpty();
	}

	@Test
	@DisplayName("취소된 회차는 첫·마지막 회차 일시에서 빠진다")
	void canceledScheduleIsExcluded() throws Exception {
		Long id = savePerformance("가상 콘서트", "CONCERT", "PUBLISHED");
		saveSchedule(id, now.plusDays(1), "CANCELED");
		saveSeat(saveSchedule(id, now.plusDays(3), "ON_SALE"), "AVAILABLE");
		saveSchedule(id, now.plusDays(9), "CANCELED");

		mockMvc.perform(get(URL))
			.andExpect(jsonPath("$.data.content[0].firstStartAt").value(now.plusDays(3).toString()))
			.andExpect(jsonPath("$.data.content[0].lastStartAt").value(now.plusDays(3).toString()));
	}

	@Test
	@DisplayName("임박순(기본)은 아직 시작하지 않은 회차 기준이고, 끝난 공연은 맨 뒤로 간다")
	void sortByImminent() throws Exception {
		saveOnSale("사흘 뒤", "CONCERT", now.plusDays(3));
		saveOnSale("내일", "CONCERT", now.plusDays(1));
		Long ended = savePerformance("끝난 공연", "CONCERT", "PUBLISHED");
		saveSchedule(ended, now.minusDays(5), "ON_SALE");
		// 첫 회차는 지났지만 다음 회차가 이틀 뒤. 첫 회차로 정렬하면 맨 앞에 와 버린다
		Long running = savePerformance("공연 중", "CONCERT", "PUBLISHED");
		saveSchedule(running, now.minusDays(1), "ON_SALE");
		saveSeat(saveSchedule(running, now.plusDays(2), "ON_SALE"), "AVAILABLE");

		List<String> expected = List.of("내일", "공연 중", "사흘 뒤", "끝난 공연");
		assertThat(titles(getList(""))).containsExactlyElementsOf(expected);
		assertThat(titles(getList("?sort=IMMINENT"))).containsExactlyElementsOf(expected);
	}

	@Test
	@DisplayName("최신 등록순은 등록 시각이 늦은 공연이 먼저 나온다")
	void sortByLatest() throws Exception {
		savePerformance("가장 먼저 등록", "CONCERT", "PUBLISHED", now.minusDays(1), now.minusDays(3));
		savePerformance("가장 늦게 등록", "CONCERT", "PUBLISHED", now.minusDays(1), now.minusDays(1));
		savePerformance("중간에 등록", "CONCERT", "PUBLISHED", now.minusDays(1), now.minusDays(2));

		assertThat(titles(getList("?sort=LATEST"))).containsExactly("가장 늦게 등록", "중간에 등록", "가장 먼저 등록");
	}

	@Test
	@DisplayName("이름순은 가나다순이고, 장르 필터와 함께 쓸 수 있다")
	void sortByTitle() throws Exception {
		saveOnSale("다 공연", "MUSICAL", now.plusDays(1));
		saveOnSale("가 공연", "MUSICAL", now.plusDays(2));
		saveOnSale("나 공연", "MUSICAL", now.plusDays(3));
		saveOnSale("가가 콘서트", "CONCERT", now.plusDays(4));

		assertThat(titles(getList("?sort=TITLE&genre=MUSICAL"))).containsExactly("가 공연", "나 공연", "다 공연");
	}

	@Test
	@DisplayName("한 번에 최대 20개까지 주고, 다음 페이지에 나머지가 겹치지 않고 나온다")
	void pageSizeIsCapped() throws Exception {
		for (int i = 1; i <= 25; i++) {
			saveOnSale("공연 %02d".formatted(i), "CONCERT", now.plusDays(1));
		}

		MvcResult first = mockMvc.perform(get(URL + "?size=100&sort=TITLE"))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(25))
			.andExpect(jsonPath("$.data.totalPages").value(2))
			.andReturn();
		MvcResult second = getList("?page=1&size=100&sort=TITLE");

		assertThat(titles(first)).hasSize(20).startsWith("공연 01");
		assertThat(titles(second)).hasSize(5).containsExactly("공연 21", "공연 22", "공연 23", "공연 24", "공연 25");
	}

	@Test
	@DisplayName("판매 상태는 오픈 전이면 예정, 좌석이 있으면 판매 중, 판매 가능 좌석이 없으면 매진, 회차가 끝났으면 종료다")
	void saleStatus() throws Exception {
		Long upcoming = savePerformance("오픈 전", "CONCERT", "PUBLISHED", now.plusDays(1), now);
		saveSchedule(upcoming, now.plusDays(5), "ON_SALE");

		saveOnSale("판매 중", "CONCERT", now.plusDays(6));

		// 판매 완료와 임시 점유만 남았으면 지금은 살 수 없으니 매진
		Long soldOut = savePerformance("매진", "CONCERT", "PUBLISHED");
		Long soldOutSchedule = saveSchedule(soldOut, now.plusDays(7), "ON_SALE");
		saveSeat(soldOutSchedule, "SOLD");
		saveSeat(soldOutSchedule, "HELD");

		// 지난 회차에만 남은 좌석이 있으면 살 수 없으니 종료
		Long ended = savePerformance("종료", "CONCERT", "PUBLISHED");
		saveSeat(saveSchedule(ended, now.minusDays(1), "ON_SALE"), "AVAILABLE");

		MvcResult result = getList("?sort=TITLE");
		String body = result.getResponse().getContentAsString();
		assertThat(statusOf(body, "오픈 전")).isEqualTo("UPCOMING");
		assertThat(statusOf(body, "판매 중")).isEqualTo("ON_SALE");
		assertThat(statusOf(body, "매진")).isEqualTo("SOLD_OUT");
		assertThat(statusOf(body, "종료")).isEqualTo("ENDED");
	}

	private String statusOf(String body, String title) {
		List<String> found = JsonPath.read(body, "$.data.content[?(@.title == '" + title + "')].saleStatus");
		return found.getFirst();
	}

	@Test
	@DisplayName("없는 장르나 정렬 값을 보내면 400 COMMON000 이다")
	void invalidParameter() throws Exception {
		mockMvc.perform(get(URL + "?genre=ROCK"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
		mockMvc.perform(get(URL + "?sort=PRICE"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}
}
