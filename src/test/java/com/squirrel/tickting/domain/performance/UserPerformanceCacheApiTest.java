package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공연 조회 캐시(목록·상세·구역 요약). 캐시를 켜고, 낡은 값이 보여도 되는 것과 절대 보이면 안 되는 것을 가른다.
 * 데이터는 SQL 로 직접 넣는다. 요청도 테스트와 같은 트랜잭션 안에서 돌아서, 캐시가 있으면 DB 를 바꿔도 응답이 그대로인지로 적중을 확인한다.
 * TTL 은 적중을 확인하는 동안 만료되지 않게 길게 두고, 만료는 Redis 에 걸린 TTL 값으로 확인한다.
 */
@Transactional
@TestPropertySource(properties = {
	"app.performance.cache.enabled=true",
	"app.performance.cache.list-ttl=60s",
	"app.performance.cache.detail-ttl=60s",
	"app.performance.cache.zone-summary-ttl=60s"
})
class UserPerformanceCacheApiTest extends IntegrationTestSupport {

	private static final String LIST_URL = "/api/v1/performances";
	private static final String DETAIL_URL = "/api/v1/performances/%d";
	private static final String ZONES_URL = "/api/v1/schedules/%d/zones";
	private static final String SEATS_URL = "/api/v1/schedules/%d/zones/%d/seats";

	private final LocalDateTime now = LocalDateTime.now().withNano(0);

	@Autowired
	private StringRedisTemplate redis;

	@PersistenceContext
	private EntityManager entityManager;

	private Long venueId;
	private Long businessId;
	private Long cancelPolicyId;

	@BeforeEach
	void setUpData() {
		clearCache();
		// 테스트 DB 에는 시드 공연이 있다. 이 테스트의 목록에 섞이지 않게 숨긴다. (트랜잭션이 끝나면 되돌려진다)
		jdbc.update("UPDATE performance SET status = 'DRAFT'");
		cancelPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, layout_key, created_at, updated_at)
			VALUES ('가상 아레나', '서울 가상구 1', '서울', 100, 'ACTIVE', 'sample-arena', ?, ?) RETURNING id
			""", Long.class, now, now);
		businessId = jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone, contact_email,
			                             terms_agreed_at, status, reject_reason, reviewed_by, reviewed_at, created_at, updated_at)
			VALUES ('(주)가상기획', '김대표', '1234567890', '01012345678', 'contact@company.com',
			        ?, 'APPROVED', NULL, 1, ?, ?, ?) RETURNING id
			""", Long.class, now, now, now, now);
	}

	/** 캐시는 Redis 에 남아서 다음 테스트와 섞일 수 있다. 앞뒤로 지운다. */
	@AfterEach
	void clearCache() {
		Set<String> keys = redis.keys("tickting:cache:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	// ---- 데이터 만들기 ----

	private Long savePerformance(String title, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, sale_open_at, sale_close_at, max_per_person,
			                         receive_type, booking_fee, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?, 'CONCERT', 12, 150, 'https://cdn.example.com/p.png', ?, ?, ?, 4, 'ONSITE', 2000, ?, ?)
			RETURNING id
			""", Long.class, businessId, venueId, cancelPolicyId, title, status, now.minusDays(1), now.plusDays(30), now, now);
	}

	private Long saveSchedule(Long performanceId) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, seats_generated_at, created_at, updated_at)
			VALUES (?, ?, 'ON_SALE', ?, ?, ?) RETURNING id
			""", Long.class, performanceId, now.plusDays(7), now, now, now);
	}

	private Long saveZone(String name) {
		return jdbc.queryForObject("""
			INSERT INTO venue_zone (venue_id, name, floor, sort_order) VALUES (?, ?, '1층', 1) RETURNING id
			""", Long.class, venueId, name);
	}

	/** 공연장 좌석 하나와 그 판매 좌석을 넣고 판매 좌석 번호를 돌려준다. */
	private Long saveScheduleSeat(Long scheduleId, Long zoneId, String status) {
		Long venueSeatId = jdbc.queryForObject("""
			INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order) VALUES (?, ?, '1열', '1', 1) RETURNING id
			""", Long.class, venueId, zoneId);
		return jdbc.queryForObject("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, status, version, updated_at)
			VALUES (?, ?, ?, ?, 0, ?) RETURNING id
			""", Long.class, scheduleId, venueSeatId, zoneId, status, now);
	}

	private String body(String url) throws Exception {
		return mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	// ---- 테스트 ----

	@Test
	@DisplayName("목록·상세·구역 요약은 캐시에서 같은 응답을 주고, 원본이 바뀌어도 TTL 안에는 이전 값이다")
	void cachedResponseIsSameAndKeptUntilExpired() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId);
		saveZone("F2구역");
		String detailUrl = DETAIL_URL.formatted(performanceId);
		String zonesUrl = ZONES_URL.formatted(scheduleId);

		String list = body(LIST_URL);
		String detail = body(detailUrl);
		String zones = body(zonesUrl);
		jdbc.update("UPDATE performance SET title = '바뀐 제목' WHERE id = ?", performanceId);
		jdbc.update("UPDATE venue_zone SET name = '바뀐 구역' WHERE venue_id = ?", venueId);
		// 요청이 테스트와 같은 트랜잭션이라 앞 조회에서 읽은 엔티티가 남아 있다. 지워야 DB 의 바뀐 값을 읽는다.
		entityManager.clear();

		// 둘째 응답은 Redis 의 JSON 을 되살려서 만든다. 첫째(DB)와 글자 그대로 같아야 날짜·열거형·null 이 새지 않은 것이다.
		assertThat(body(LIST_URL)).isEqualTo(list);
		assertThat(body(detailUrl)).isEqualTo(detail);
		assertThat(body(zonesUrl)).isEqualTo(zones);
		assertThat(JsonPath.<String>read(list, "$.data.content[0].title")).isEqualTo("가상 콘서트");

		// 만료되도록 Redis 에 TTL 이 걸려 있고, 지워지면 바뀐 값이 읽힌다.
		Long ttl = redis.getExpire("tickting:cache:performance-detail:" + performanceId);
		assertThat(ttl).isBetween(1L, Duration.ofSeconds(60).toSeconds());
		clearCache();
		assertThat(JsonPath.<String>read(body(detailUrl), "$.data.title")).isEqualTo("바뀐 제목");
		assertThat(JsonPath.<String>read(body(zonesUrl), "$.data.zones[0].name")).isEqualTo("바뀐 구역");
	}

	@Test
	@DisplayName("구역 좌석 상세는 캐시하지 않는다. 좌석 상태가 바뀌면 바로 보인다")
	void zoneSeatsAreNeverCached() throws Exception {
		Long scheduleId = saveSchedule(savePerformance("가상 콘서트", "PUBLISHED"));
		Long zoneId = saveZone("F2구역");
		Long scheduleSeatId = saveScheduleSeat(scheduleId, zoneId, "AVAILABLE");
		String seatsUrl = SEATS_URL.formatted(scheduleId, zoneId);

		assertThat(JsonPath.<String>read(body(seatsUrl), "$.data.seats[0].status")).isEqualTo("AVAILABLE");
		jdbc.update("UPDATE schedule_seat SET status = 'HELD' WHERE id = ?", scheduleSeatId);

		assertThat(JsonPath.<String>read(body(seatsUrl), "$.data.seats[0].status")).isEqualTo("HELD");
	}

	@Test
	@DisplayName("검색어가 있는 목록은 캐시하지 않는다")
	void keywordSearchIsNotCached() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");

		assertThat(JsonPath.<Integer>read(search("가상"), "$.data.totalElements")).isEqualTo(1);
		jdbc.update("UPDATE performance SET title = '다른 공연' WHERE id = ?", performanceId);

		assertThat(JsonPath.<Integer>read(search("가상"), "$.data.totalElements")).isZero();
	}

	private String search(String keyword) throws Exception {
		return mockMvc.perform(get(LIST_URL).param("keyword", keyword))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	@DisplayName("없는 공연의 404 는 캐시하지 않는다. 공개로 바뀌면 바로 보인다")
	void notFoundIsNotCached() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "DRAFT");
		String detailUrl = DETAIL_URL.formatted(performanceId);

		mockMvc.perform(get(detailUrl)).andExpect(status().isNotFound());
		jdbc.update("UPDATE performance SET status = 'PUBLISHED' WHERE id = ?", performanceId);

		assertThat(JsonPath.<String>read(body(detailUrl), "$.data.title")).isEqualTo("가상 콘서트");
	}
}
