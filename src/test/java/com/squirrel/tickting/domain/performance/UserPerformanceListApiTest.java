package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class UserPerformanceListApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/performances";

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private Long venueId;
	private Long cancelPolicyId;
	private long venueSeatSequence;

	/**
	 * 테스트 DB 에는 로컬 시드 공연이 들어 있다. 그래서 지우지 않고,
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
	@DisplayName("작성 중(DRAFT)·취소(CANCELED) 공연은 목록에 보이지 않는다")
	void onlyPublished() throws Exception {
		saveOnSale("공개 공연", "CONCERT", now.plusDays(3));
		savePerformance("작성 중 공연", "CONCERT", "DRAFT");
		savePerformance("취소된 공연", "CONCERT", "CANCELED");

		assertThat(titles(getList(""))).containsExactly("공개 공연");
	}
}
