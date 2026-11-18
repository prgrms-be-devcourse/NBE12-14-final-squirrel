package com.squirrel.tickting.domain.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 예매 목록(BKG-04). 공연·회차·예매·예매 좌석을 SQL 로 직접 넣고 확인한다.
 * (예매 생성·취소 API 는 다른 파트 작업이라 기대지 않는다. 일부 취소는 예매 좌석의 active 를 false 로 넣어 만든다)
 * 테스트 트랜잭션 안에서 넣고 끝나면 되돌린다.
 */
@Transactional
class UserBookingListApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/bookings";
	private static final long ME = 900_001L;
	private static final long OTHER = 900_002L;

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private Long cancelPolicyId;
	private int bookingNoSequence;

	@BeforeEach
	void setUpData() {
		cancelPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
	}

	// ---- 데이터 만들기 ----

	private String bearer(Long id, AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(id, type, role, businessId));
	}

	private String userBearer(long userId) {
		return bearer(userId, AccountType.USER, null, null);
	}

	private Long savePerformance(String title, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, sale_open_at, sale_close_at, max_per_person,
			                         receive_type, booking_fee, created_at, updated_at)
			VALUES (1, 1, 1, ?, ?, 'CONCERT', 12, 150, ?, ?, ?, ?, 4, 'ONSITE', 2000, ?, ?)
			RETURNING id
			""", Long.class, cancelPolicyId, title, "https://cdn.example.com/" + title + ".png", status,
			now.minusDays(1), now.plusDays(30), now, now);
	}

	private Long saveSchedule(Long performanceId, LocalDateTime startAt, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?) RETURNING id
			""", Long.class, performanceId, startAt, status, now, now);
	}

	private Long saveBooking(long userId, Long performanceId, Long scheduleId, String status, int totalAmount,
		LocalDateTime createdAt) {
		return jdbc.queryForObject("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, 'ONSITE', '홍길동', '01012345678', 'user@example.com', ?, 0, ?, '[]'::jsonb, ?, ?, ?)
			RETURNING id
			""", Long.class, "TEST" + (++bookingNoSequence), userId, scheduleId, performanceId, status, totalAmount,
			totalAmount, createdAt.plusMinutes(7), createdAt, createdAt);
	}

	// ---- 테스트 ----

	@Test
	@DisplayName("다른 예매자의 예매는 내 목록에 나오지 않는다")
	void onlyMyBookings() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		Long mine = saveBooking(ME, performanceId, scheduleId, "PAID", 156000, now.minusDays(1));
		saveBooking(OTHER, performanceId, scheduleId, "PAID", 156000, now.minusHours(1));

		mockMvc.perform(get(URL).header("Authorization", userBearer(ME)))
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].bookingId").value(mine));
	}
}
