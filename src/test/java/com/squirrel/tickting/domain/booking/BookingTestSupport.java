package com.squirrel.tickting.domain.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 예매 관련 통합 테스트가 같이 쓰는 데이터 만들기. 공연장·공연·회차·등급·판매 좌석을 SQL 로 넣고, 예매자는 실제 가입 API 로 만든다.
 * 동시성 테스트는 스레드마다 트랜잭션이 따로 있어야 해서 테스트 트랜잭션으로 감싸지 않고, 만든 데이터를 직접 지운다.
 * 시각은 자바에서 만든 값을 넣는다. (컨테이너 DB 와 애플리케이션의 시간대가 달라도 어긋나지 않게)
 */
public abstract class BookingTestSupport extends IntegrationTestSupport {

	protected static final String SIGNUP = "/api/v1/auth/users/signup";
	protected static final int MAX_PER_PERSON = 4;

	protected final LocalDateTime now = LocalDateTime.now().withNano(0);
	protected Long venueId;
	protected Long zoneId;
	protected Long cancelPolicyId;
	protected List<Long> venueSeatIds;
	protected final List<Long> performanceIds = new ArrayList<>();

	@BeforeEach
	void setUpData() {
		jdbc.execute("TRUNCATE TABLE booking, booking_seat, schedule_seat RESTART IDENTITY");
		cancelPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('가상 아레나', '서울 가상구 1', '서울', 100, 'ACTIVE', ?, ?) RETURNING id
			""", Long.class, now, now);
		zoneId = jdbc.queryForObject("""
			INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, 'F2구역', 1) RETURNING id
			""", Long.class, venueId);
		venueSeatIds = new ArrayList<>();
		for (int number = 1; number <= 12; number++) {
			venueSeatIds.add(jdbc.queryForObject("""
				INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order)
				VALUES (?, ?, '9열', ?, ?) RETURNING id
				""", Long.class, venueId, zoneId, String.valueOf(number), number));
		}
	}

	@AfterEach
	void cleanUpData() {
		for (Long performanceId : performanceIds) {
			jdbc.update("DELETE FROM performance_schedule WHERE performance_id = ?", performanceId);
			jdbc.update("DELETE FROM seat_grade WHERE performance_id = ?", performanceId);
			jdbc.update("DELETE FROM performance WHERE id = ?", performanceId);
		}
		jdbc.update("DELETE FROM venue_seat WHERE venue_id = ?", venueId);
		jdbc.update("DELETE FROM venue_zone WHERE venue_id = ?", venueId);
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
		jdbc.execute("TRUNCATE TABLE booking, booking_seat, schedule_seat RESTART IDENTITY");
	}

	// ---------------------------------------------------------------- 데이터 만들기

	/** 공연·회차·등급(R, 154,000원)과 판매 좌석. seatIds 는 판매 좌석(schedule_seat) 번호다. */
	protected record Show(Long performanceId, Long scheduleId, List<Long> seatIds) {
	}

	protected Show show(int seatCount) {
		return show(seatCount, "PUBLISHED", "ON_SALE", now.plusDays(10), now.minusDays(1), now.plusDays(30), "BOTH", 2000);
	}

	protected Show show(int seatCount, String performanceStatus, String scheduleStatus, LocalDateTime startAt,
		LocalDateTime saleOpenAt, LocalDateTime saleCloseAt, String receiveType, Integer bookingFee) {
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, cast_info, poster_url, notice, guide, status, sale_open_at,
			                         sale_close_at, max_per_person, receive_type, booking_fee, created_at, updated_at)
			VALUES (1, ?, 1, ?, '가상 콘서트', 'CONCERT', 12, 150, '가상 밴드', 'https://cdn.example.com/p.png',
			        '입장 안내', '촬영 금지', ?, ?, ?, ?, ?, ?, ?, ?)
			RETURNING id
			""", Long.class, venueId, cancelPolicyId, performanceStatus, saleOpenAt, saleCloseAt, MAX_PER_PERSON,
			receiveType, bookingFee, now, now);
		performanceIds.add(performanceId);
		Long scheduleId = jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?) RETURNING id
			""", Long.class, performanceId, startAt, scheduleStatus, now, now);
		Long gradeId = jdbc.queryForObject("""
			INSERT INTO seat_grade (performance_id, name, price) VALUES (?, 'R', 154000) RETURNING id
			""", Long.class, performanceId);
		List<Long> seatIds = new ArrayList<>();
		for (int i = 0; i < seatCount; i++) {
			seatIds.add(saveSeat(scheduleId, venueSeatIds.get(i), gradeId, "AVAILABLE"));
		}
		return new Show(performanceId, scheduleId, seatIds);
	}

	protected Long saveSeat(Long scheduleId, Long venueSeatId, Long gradeId, String status) {
		return jdbc.queryForObject("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, grade_id, price, status, version, updated_at)
			VALUES (?, ?, ?, ?, 154000, ?, 0, ?) RETURNING id
			""", Long.class, scheduleId, venueSeatId, zoneId, gradeId, status, now);
	}

	protected record User(Long id, String bearer) {
	}

	/** 실제 가입 API 로 예매자를 만들고 액세스 토큰을 받는다. 이름은 본인인증 값(홍길동)으로 들어간다. */
	protected User signup(String name) throws Exception {
		String email = name + "@example.com";
		MvcResult result = postJson(SIGNUP, userSignupBody(email, "ci-" + name))
			.andExpect(status().isCreated())
			.andReturn();
		Long id = jdbc.queryForObject("SELECT id FROM user_member WHERE email = ?", Long.class, email);
		return new User(id, "Bearer " + accessToken(result));
	}

	protected ResultActions book(String bearer, Long scheduleId, List<Long> seatIds) throws Exception {
		return mockMvc.perform(post("/api/v1/schedules/" + scheduleId + "/bookings")
			.header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("seatIds", seatIds))));
	}

	/** 성공하면 "OK", 실패하면 에러 코드. 동시성 테스트에서 결과를 모아 세는 데 쓴다. */
	protected String bookForResult(String bearer, Long scheduleId, List<Long> seatIds) throws Exception {
		MvcResult result = book(bearer, scheduleId, seatIds).andReturn();
		return result.getResponse().getStatus() == 201 ? "OK" : read(result, "$.code");
	}

	protected String seatStatus(Long seatId) {
		return jdbc.queryForObject("SELECT status FROM schedule_seat WHERE id = ?", String.class, seatId);
	}

	protected long countOf(String result, List<String> results) {
		return results.stream().filter(result::equals).count();
	}

}
