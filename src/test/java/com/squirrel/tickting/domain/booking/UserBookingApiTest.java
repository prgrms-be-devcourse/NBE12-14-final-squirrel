package com.squirrel.tickting.domain.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 예매 목록(BKG-04)·상세(BKG-05). 공연·회차·예매·예매 좌석을 SQL 로 직접 넣고 확인한다.
 * (예매 생성·취소 API 는 다른 파트 작업이라 기대지 않는다. 일부 취소는 예매 좌석의 active 를 false 로 넣어 만든다)
 * 테스트 트랜잭션 안에서 넣고 끝나면 되돌린다.
 */
@Transactional
class UserBookingApiTest extends IntegrationTestSupport {

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

	@Test
	@DisplayName("만료된 예매는 내 목록에 나오지 않고(결제 대기는 나온다), 만료 상태로 고르면 400 이다")
	void hidesExpired() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		Long paid = saveBooking(ME, performanceId, scheduleId, "PAID", 156000, now.minusDays(1));
		Long pending = saveBooking(ME, performanceId, scheduleId, "PENDING", 156000, now.minusMinutes(3));
		saveBooking(ME, performanceId, scheduleId, "EXPIRED", 156000, now.minusHours(1));

		mockMvc.perform(get(URL).header("Authorization", userBearer(ME)))
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.content[0].bookingId").value(pending))
			.andExpect(jsonPath("$.data.content[1].bookingId").value(paid));

		mockMvc.perform(get(URL).param("status", "EXPIRED").header("Authorization", userBearer(ME)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}

	@Test
	@DisplayName("다른 예매자의 예매 상세는 404 이다")
	void otherUsersBookingDetailIsNotFound() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		Long others = saveBooking(OTHER, performanceId, scheduleId, "PAID", 156000, now.minusDays(1));

		mockMvc.perform(get(URL + "/" + others).header("Authorization", userBearer(ME)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING006"));
	}

	@Test
	@DisplayName("결제 대기 예매도 상세를 볼 수 있다 (결제 화면이 결제 제한 시각을 읽는다)")
	void pendingBookingDetail() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		LocalDateTime createdAt = now.minusMinutes(3);
		Long pending = saveBooking(ME, performanceId, scheduleId, "PENDING", 156000, createdAt);

		mockMvc.perform(get(URL + "/" + pending).header("Authorization", userBearer(ME)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("PENDING"))
			.andExpect(jsonPath("$.data.expiresAt").value(iso(createdAt.plusMinutes(7))))
			.andExpect(jsonPath("$.data.payment").doesNotExist())
			.andExpect(jsonPath("$.data.performance.performanceId").value(performanceId));
	}

	@Test
	@DisplayName("결제 완료 예매 상세에 승인된 결제와 예매 당시 취소 규정이 나온다")
	void paidBookingDetail() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		Long paid = saveBooking(ME, performanceId, scheduleId, "PAID", 156000, now.minusDays(1));
		jdbc.update("""
			UPDATE booking SET cancel_policy_snapshot = ?::jsonb WHERE id = ?
			""", """
			[{"daysFrom": 0, "daysTo": 0, "feeRate": 0, "cancelable": false},
			 {"daysFrom": 7, "daysTo": null, "feeRate": 0, "cancelable": true}]
			""", paid);
		jdbc.update("""
			INSERT INTO booking_seat (booking_id, schedule_seat_id, zone_name, row_label, seat_number, grade_name,
			                          price, booking_fee, active)
			VALUES (?, NULL, 'F2구역', '9열', '6', 'R', 154000, 2000, true)
			""", paid);
		jdbc.update("""
			INSERT INTO payment (booking_id, order_id, method, amount, status, fail_reason, created_at)
			VALUES (?, 'BK-FAILED-1', NULL, 156000, 'FAILED', '한도 초과', ?)
			""", paid, now.minusDays(1));
		jdbc.update("""
			INSERT INTO payment (booking_id, order_id, payment_key, method, amount, status, approved_at, created_at)
			VALUES (?, 'BK-DONE-2', 'test_key_2', '카드', 156000, 'DONE', ?, ?)
			""", paid, now.minusDays(1), now.minusDays(1));

		mockMvc.perform(get(URL + "/" + paid).header("Authorization", userBearer(ME)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.payment.status").value("DONE"))
			.andExpect(jsonPath("$.data.payment.method").value("카드"))
			.andExpect(jsonPath("$.data.seats[0].zoneName").value("F2구역"))
			.andExpect(jsonPath("$.data.cancelPolicy[0].daysFrom").value(7))
			.andExpect(jsonPath("$.data.cancelPolicy[1].daysFrom").value(0))
			.andExpect(jsonPath("$.data.expiresAt").doesNotExist());
	}

	private static String iso(LocalDateTime time) {
		return time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
	}
}
