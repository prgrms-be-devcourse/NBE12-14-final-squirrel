package com.squirrel.tickting.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 회원 탈퇴(AUTH-08). 회원은 실제 가입·v2 로그인 API 로 만들고, 예매는 SQL 로 직접 넣는다.
 * (예매 생성 API 는 다른 파트 작업이라 기대지 않는다) 공통 틀이 예매 표를 비우지 않아서 앞뒤로 직접 비운다.
 */
class UserWithdrawApiTest extends IntegrationTestSupport {

	private static final String SIGNUP = "/api/v2/auth/users/signup";
	private static final String LOGIN = "/api/v2/auth/users/login";
	private static final String ME = "/api/v1/users/me";

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private final List<Long> performanceIds = new ArrayList<>();
	private int bookingNoSequence;

	@BeforeEach
	@AfterEach
	void clearBookings() {
		jdbc.execute("TRUNCATE TABLE booking RESTART IDENTITY");
		performanceIds.forEach(id -> {
			jdbc.update("DELETE FROM performance_schedule WHERE performance_id = ?", id);
			jdbc.update("DELETE FROM performance WHERE id = ?", id);
		});
		performanceIds.clear();
	}

	// ---- 데이터 만들기 ----

	/** 가입하고 v2 로 로그인해서 SESSION 쿠키를 돌려준다. */
	private Cookie signupAndLogin(String email) throws Exception {
		postJson(SIGNUP, userSignupBody(email)).andExpect(status().isCreated());
		MvcResult login = postJson(LOGIN, loginBody(email, PASSWORD)).andExpect(status().isOk()).andReturn();
		return login.getResponse().getHeaders("Set-Cookie").stream()
			.filter(header -> header.startsWith("SESSION="))
			.map(header -> header.substring("SESSION=".length(), header.indexOf(';')))
			.findFirst()
			.map(value -> new Cookie("SESSION", value))
			.orElseThrow();
	}

	private Long userId(String email) {
		return jdbc.queryForObject("SELECT id FROM user_member WHERE email = ?", Long.class, email);
	}

	/** 공연 하나와 그 회차 하나. */
	private Long saveSchedule(LocalDateTime startAt) {
		Long cancelPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, sale_open_at, sale_close_at, max_per_person,
			                         receive_type, booking_fee, created_at, updated_at)
			VALUES (1, 1, 1, ?, '가상 콘서트', 'CONCERT', 12, 150, 'https://cdn.example.com/poster.png', 'PUBLISHED', ?, ?, 4, 'ONSITE', 2000, ?, ?)
			RETURNING id
			""", Long.class, cancelPolicyId, now.minusDays(30), now.plusDays(30), now, now);
		performanceIds.add(performanceId);
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, 'ON_SALE', ?, ?) RETURNING id
			""", Long.class, performanceId, startAt, now, now);
	}

	private void saveBooking(Long userId, Long scheduleId, String status, LocalDateTime expiresAt) {
		jdbc.update("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			SELECT ?, ?, s.id, s.performance_id, p.business_id, ?, 'ONSITE', '홍길동', '01012345678', 'user@example.com',
			       154000, 2000, 156000, '[]'::jsonb, ?, ?, ?
			FROM performance_schedule s JOIN performance p ON p.id = s.performance_id WHERE s.id = ?
			""", "TEST" + (++bookingNoSequence), userId, status, expiresAt, now, now, scheduleId);
	}

	// ---- 테스트 ----

	@Test
	@DisplayName("탈퇴하면 개인정보가 지워지고 지금 세션이 끝나며, 같은 이메일·본인인증으로 다시 가입할 수 있다")
	void withdraw() throws Exception {
		Cookie session = signupAndLogin("user@example.com");
		Long userId = userId("user@example.com");
		// 이미 시작한 공연(1시간 전 시작, 아직 공연 중)의 결제 완료 예매, 만료된 예매, 제한 시각이 지난 결제 대기는 탈퇴를 막지 않는다
		saveBooking(userId, saveSchedule(now.minusHours(1)), "PAID", now.minusDays(2));
		saveBooking(userId, saveSchedule(now.plusDays(10)), "EXPIRED", now.minusHours(1));
		saveBooking(userId, saveSchedule(now.plusDays(10)), "PENDING", now.minusMinutes(1));

		mockMvc.perform(delete(ME).cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_member WHERE id = ?", userId);
		assertThat(row)
			.containsEntry("email", "deleted-" + userId + "@withdrawn.invalid")
			.containsEntry("name", "탈퇴회원")
			.containsEntry("nickname", "탈퇴회원")
			.containsEntry("phone", "-");
		assertThat(row.get("deleted_at")).isNotNull();
		assertThat(row.get("password")).isEqualTo("WITHDRAWN");

		mockMvc.perform(get(ME).cookie(session))
			.andExpect(status().isUnauthorized());
		postJson(LOGIN, loginBody("user@example.com", PASSWORD))
			.andExpect(status().isUnauthorized());
		postJson(SIGNUP, userSignupBody("user@example.com"))
			.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("결제 대기 예매나 아직 시작하지 않은 공연의 결제 완료 예매가 있으면 409 USER003 이고 탈퇴되지 않는다")
	void ongoingBookingBlocksWithdraw() throws Exception {
		Cookie session = signupAndLogin("user@example.com");
		Long userId = userId("user@example.com");

		// 1. 결제 제한 시각이 남은 결제 대기
		saveBooking(userId, saveSchedule(now.plusDays(10)), "PENDING", now.plusMinutes(5));
		assertBlocked(session, userId);

		// 2. 1시간 뒤에 시작하는 공연의 일부 취소 예매 (당일이라 취소는 못 하지만 시작 전)
		jdbc.execute("TRUNCATE TABLE booking RESTART IDENTITY");
		saveBooking(userId, saveSchedule(now.plusHours(1)), "PARTIAL_CANCELED", now.minusDays(1));
		assertBlocked(session, userId);
	}

	private void assertBlocked(Cookie session, Long userId) throws Exception {
		mockMvc.perform(delete(ME).cookie(session))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("USER003"));

		assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM user_member WHERE id = ?", Boolean.class, userId))
			.isTrue();
		mockMvc.perform(get(ME).cookie(session))
			.andExpect(status().isOk());
	}
}
