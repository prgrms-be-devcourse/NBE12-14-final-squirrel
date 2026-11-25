package com.squirrel.tickting.domain.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 역할별 예매 조회(BookingQueryService)의 범위. 판매자에게 다른 사업체의 예매가 보이지 않는지를 지킨다.
 * 예매자 범위는 UserBookingApiTest 가, 역할별 경로 권한(403)은 SecurityConfigTest 가 확인한다.
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않고, 시드에 없는 사업체 번호로 넣은 행만 지운다.
 */
class BookingScopeQueryApiTest extends IntegrationTestSupport {

	private static final long MY_BUSINESS = 873L;
	private static final long OTHER_BUSINESS = 874L;
	private static final LocalDateTime BASE = LocalDateTime.of(2030, 3, 1, 10, 0);

	@AfterEach
	void deleteInsertedRows() {
		String businesses = MY_BUSINESS + ", " + OTHER_BUSINESS;
		jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (SELECT id FROM booking WHERE business_id IN ("
			+ businesses + "))");
		jdbc.update("DELETE FROM booking WHERE business_id IN (" + businesses + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (SELECT id FROM performance "
			+ "WHERE business_id IN (" + businesses + "))");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + businesses + ")");
	}

	private String sellerToken(long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(
			new CustomUserDetails(1L, AccountType.SELLER, "OWNER", businessId));
	}

	private String adminToken() {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.ADMIN, null, null));
	}

	private ResultActions sellerList(String query) throws Exception {
		return mockMvc.perform(get("/api/v1/seller/bookings" + query).header("Authorization", sellerToken(MY_BUSINESS)));
	}

	/** 공연과 회차 하나를 넣고 회차 번호를 돌려준다. */
	private Long insertSchedule(long businessId, String title) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		Long venueId = jdbc.queryForObject("SELECT id FROM venue ORDER BY id LIMIT 1", Long.class);
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, ?, 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', 'PUBLISHED', NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, title);
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, 'ON_SALE', NOW(), NOW())
			RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(BASE.plusDays(100)));
	}

	/** 회차에 예매 하나와 좌석 하나를 넣는다. 사업체 번호는 예매 생성처럼 공연에서 복사한다. */
	private Long insertBooking(Long scheduleId, String no, String status, int day) {
		Timestamp createdAt = Timestamp.valueOf(BASE.plusDays(day));
		Long bookingId = jdbc.queryForObject("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			SELECT ?, 1, s.id, p.id, p.business_id, ?, 'ONSITE', '홍길동', '01012345678', 'hong@example.com',
			       154000, 0, 154000, '[]'::jsonb, ?, ?, ?
			  FROM performance_schedule s JOIN performance p ON p.id = s.performance_id
			 WHERE s.id = ?
			RETURNING id
			""", Long.class, no, status, createdAt, createdAt, createdAt, scheduleId);
		jdbc.update("""
			INSERT INTO booking_seat (booking_id, zone_name, row_label, seat_number, grade_name, price, booking_fee,
			                          active)
			VALUES (?, 'F2구역', '9열', ?, 'R', 154000, 0, true)
			""", bookingId, String.valueOf(System.nanoTime()));
		return bookingId;
	}

	@Test
	@DisplayName("판매자 목록에는 내 사업체 공연의 예매만 나오고, 같은 제목의 다른 사업체 공연 예매는 어떤 조건으로도 보이지 않는다")
	void sellerSeesOnlyOwnBusiness() throws Exception {
		Long concert = insertSchedule(MY_BUSINESS, "가을 콘서트");
		Long musical = insertSchedule(MY_BUSINESS, "봄 뮤지컬");
		Long othersConcert = insertSchedule(OTHER_BUSINESS, "가을 콘서트");
		insertBooking(concert, "MY-1", "PAID", 1);
		insertBooking(musical, "MY-2", "CANCELED", 2);
		insertBooking(musical, "MY-3", "PAID", 3);
		insertBooking(othersConcert, "OTHER-1", "PAID", 4);

		// 상태를 비우면 남은 표가 있는 예매만, 최신 예매순
		sellerList("")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("MY-3"))
			.andExpect(jsonPath("$.data.content[1].bookingNo").value("MY-1"));
		sellerList("?title=콘서트")
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("MY-1"));
		sellerList("?status=PAID&status=CANCELED")
			.andExpect(jsonPath("$.data.totalElements").value(3));
		sellerList("?status=CANCELED&from=2030-03-03&to=2030-03-03")
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("MY-2"));
		// 사용자가 넣은 % 는 와일드카드가 아니라 글자로 찾는다
		sellerList("?title=%25")
			.andExpect(jsonPath("$.data.totalElements").value(0));
	}

	@Test
	@DisplayName("다른 사업체 공연의 예매 상세는 판매자에게 404 이고, 관리자는 두 사업체 예매를 모두 본다")
	void sellerDetailIsScopedAndAdminSeesAll() throws Exception {
		Long mine = insertBooking(insertSchedule(MY_BUSINESS, "가을 콘서트"), "MY-1", "PAID", 1);
		Long others = insertBooking(insertSchedule(OTHER_BUSINESS, "겨울 연극"), "OTHER-1", "PAID", 2);

		mockMvc.perform(get("/api/v1/seller/bookings/{id}", mine).header("Authorization", sellerToken(MY_BUSINESS)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.performanceTitle").value("가을 콘서트"));
		mockMvc.perform(get("/api/v1/seller/bookings/{id}", others).header("Authorization", sellerToken(MY_BUSINESS)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING006"));

		mockMvc.perform(get("/api/v1/admin/bookings?from=2030-03-02&to=2030-03-03")
				.header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2));
		mockMvc.perform(get("/api/v1/admin/bookings/{id}", others).header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.businessId").value(OTHER_BUSINESS));
	}
}
