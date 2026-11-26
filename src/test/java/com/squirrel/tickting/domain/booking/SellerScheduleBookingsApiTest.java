package com.squirrel.tickting.domain.booking;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 회차별 예매 목록(SLR-18).
 * 시드 공연을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호로 공연·회차를 넣고,
 * 끝나면 그 사업체의 공연·회차·예매만 지운다. 예매와 예매 좌석은 SQL 로 넣는다.
 */
class SellerScheduleBookingsApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/schedules/{scheduleId}/bookings";
	private static final long MY_BUSINESS = 895L;
	private static final long OTHER_BUSINESS = 896L;
	private static final LocalDateTime BASE = LocalDateTime.of(2030, 1, 1, 10, 0);

	@AfterEach
	void deleteInsertedRows() {
		String performances = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		String schedules = "SELECT id FROM performance_schedule WHERE performance_id IN (" + performances + ")";
		jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (SELECT id FROM booking WHERE schedule_id IN ("
			+ schedules + "))");
		jdbc.update("DELETE FROM booking WHERE schedule_id IN (" + schedules + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + performances + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")");
	}

	private Cookie token(AccountType type, String role, Long businessId) {
		return sessionCookie(new CustomUserDetails(1L, type, role, businessId));
	}

	private ResultActions call(Long scheduleId, String query) throws Exception {
		return mockMvc.perform(get(URL + query, scheduleId)
			.cookie(token(AccountType.SELLER, "OWNER", MY_BUSINESS)));
	}

	private Long insertSchedule(long businessId, String performanceStatus, String scheduleStatus) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		Long venueId = jdbc.queryForObject("SELECT id FROM venue ORDER BY id LIMIT 1", Long.class);
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '예매 목록 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', ?, NOW(),
			        NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, performanceStatus);
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(BASE.plusDays(100)), scheduleStatus);
	}

	private Long insertSchedule() {
		return insertSchedule(MY_BUSINESS, "PUBLISHED", "ON_SALE");
	}

	private Long insertBooking(Long scheduleId, String no, String status, String receiveType, int minutes) {
		boolean delivery = "DELIVERY".equals(receiveType);
		Long performanceId = jdbc.queryForObject("SELECT performance_id FROM performance_schedule WHERE id = ?",
			Long.class, scheduleId);
		return jdbc.queryForObject("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, recipient_name, recipient_phone,
			                     recipient_address, ticket_amount, booking_fee_amount, total_amount,
			                     cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, 1, ?, ?, COALESCE((SELECT business_id FROM performance WHERE id = ?), 0), ?, ?, '홍길동',
			        '010-1234-5678', 'hong@example.com', ?, ?, ?, 0, 0, 0, '[]'::jsonb, ?, ?, ?)
			RETURNING id
			""", Long.class, no, scheduleId, performanceId, performanceId, status, receiveType,
			delivery ? "김받는" : null, delivery ? "010-9999-0000" : null, delivery ? "서울 가상구 1" : null,
			Timestamp.valueOf(BASE), Timestamp.valueOf(BASE.plusMinutes(minutes)), Timestamp.valueOf(BASE));
	}

	private void insertSeat(Long bookingId, String grade, int price, boolean active) {
		jdbc.update("""
			INSERT INTO booking_seat (booking_id, zone_name, row_label, seat_number, grade_name, price, booking_fee,
			                          active)
			VALUES (?, 'F2구역', '9열', ?, ?, ?, 0, ?)
			""", bookingId, String.valueOf(System.nanoTime()), grade, price, active);
	}

	@Test
	@DisplayName("상태를 주지 않으면 결제 완료·일부 취소만 나오고, status 로 전체 취소·대기·만료를 따로 볼 수 있다")
	void defaultStatusesAndStatusFilter() throws Exception {
		Long scheduleId = insertSchedule();
		insertBooking(scheduleId, "B-PAID", "PAID", "ONSITE", 1);
		insertBooking(scheduleId, "B-PART", "PARTIAL_CANCELED", "ONSITE", 2);
		insertBooking(scheduleId, "B-CANCEL", "CANCELED", "ONSITE", 3);
		insertBooking(scheduleId, "B-PEND", "PENDING", "ONSITE", 4);
		insertBooking(scheduleId, "B-EXP", "EXPIRED", "ONSITE", 5);

		call(scheduleId, "")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("B-PART"))
			.andExpect(jsonPath("$.data.content[1].bookingNo").value("B-PAID"));
		call(scheduleId, "?status=CANCELED")
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("B-CANCEL"));
		call(scheduleId, "?status=PENDING")
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("B-PEND"));
		call(scheduleId, "?status=EXPIRED")
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("B-EXP"));
		call(scheduleId, "?status=NOPE")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}

	@Test
	@DisplayName("예매 번호는 정확히 일치할 때만 찾고, 일부만 넣거나 다른 회차의 번호면 빈 목록이며, 수령 방법으로도 거른다")
	void bookingNoExactMatchAndReceiveType() throws Exception {
		Long scheduleId = insertSchedule();
		Long otherSchedule = insertSchedule();
		insertBooking(scheduleId, "20300101ABCDEF1", "PAID", "ONSITE", 1);
		insertBooking(scheduleId, "20300101ABCDEF2", "PAID", "DELIVERY", 2);
		insertBooking(otherSchedule, "20300101ZZZZZZ1", "PAID", "ONSITE", 1);

		call(scheduleId, "?bookingNo=20300101ABCDEF1")
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("20300101ABCDEF1"));
		call(scheduleId, "?bookingNo=20300101ABC")
			.andExpect(jsonPath("$.data.content").isEmpty());
		call(scheduleId, "?bookingNo=20300101ZZZZZZ1")
			.andExpect(jsonPath("$.data.content").isEmpty());
		call(scheduleId, "?bookingNo=20300101ABCDEF1&status=CANCELED")
			.andExpect(jsonPath("$.data.content").isEmpty());
		call(scheduleId, "?receiveType=DELIVERY")
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("20300101ABCDEF2"));
	}

	@Test
	@DisplayName("예매 시각이 같으면 번호가 큰 순이고, 크기는 20으로 줄어들며, 범위 밖 페이지는 빈 content 로 200이다")
	void orderingAndPaging() throws Exception {
		Long scheduleId = insertSchedule();
		insertBooking(scheduleId, "SAME-1", "PAID", "ONSITE", 5);
		insertBooking(scheduleId, "SAME-2", "PAID", "ONSITE", 5);
		insertBooking(scheduleId, "OLD", "PAID", "ONSITE", 1);
		for (int i = 0; i < 20; i++) {
			insertBooking(scheduleId, "MORE-" + i, "PAID", "ONSITE", 10 + i);
		}

		call(scheduleId, "?size=100&sort=bookingNo,asc")
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(23))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("MORE-19"));
		call(scheduleId, "?page=1")
			.andExpect(jsonPath("$.data.content.length()").value(3))
			.andExpect(jsonPath("$.data.content[0].bookingNo").value("SAME-2"))
			.andExpect(jsonPath("$.data.content[1].bookingNo").value("SAME-1"))
			.andExpect(jsonPath("$.data.content[2].bookingNo").value("OLD"));
		call(scheduleId, "?page=9")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty());
	}

	@Test
	@DisplayName("남은 표와 취소한 표를 등급·가격별로 세고 가격 높은 순으로 주며, 수수료 없는 남은 표값 합을 준다")
	void ticketAmounts() throws Exception {
		Long scheduleId = insertSchedule();
		Long partial = insertBooking(scheduleId, "B-PART", "PARTIAL_CANCELED", "ONSITE", 1);
		insertSeat(partial, "S", 100000, true);
		insertSeat(partial, "VIP", 150000, true);
		insertSeat(partial, "VIP", 150000, true);
		insertSeat(partial, "S", 100000, false);
		Long canceled = insertBooking(scheduleId, "B-CANCEL", "CANCELED", "ONSITE", 2);
		insertSeat(canceled, "R", 120000, false);

		call(scheduleId, "")
			.andExpect(jsonPath("$.data.content[0].seatCount").value(3))
			.andExpect(jsonPath("$.data.content[0].ticketAmount").value(400000))
			.andExpect(jsonPath("$.data.content[0].tickets[0].gradeName").value("VIP"))
			.andExpect(jsonPath("$.data.content[0].tickets[0].count").value(2))
			.andExpect(jsonPath("$.data.content[0].tickets[0].amount").value(300000))
			.andExpect(jsonPath("$.data.content[0].tickets[1].gradeName").value("S"))
			.andExpect(jsonPath("$.data.content[0].tickets[1].price").value(100000))
			.andExpect(jsonPath("$.data.content[0].canceledSeatCount").value(1))
			.andExpect(jsonPath("$.data.content[0].canceledTickets[0].gradeName").value("S"))
			.andExpect(jsonPath("$.data.content[0].canceledTickets[0].amount").value(100000));
		call(scheduleId, "?status=CANCELED")
			.andExpect(jsonPath("$.data.content[0].tickets").isEmpty())
			.andExpect(jsonPath("$.data.content[0].seatCount").value(0))
			.andExpect(jsonPath("$.data.content[0].ticketAmount").value(0))
			.andExpect(jsonPath("$.data.content[0].canceledTickets[0].gradeName").value("R"));
	}

	@Test
	@DisplayName("배송 정보는 배송 예매에만 있고, 담당자에게도 이름·전화번호·배송지가 그대로 나가며 이메일은 나가지 않는다")
	void deliveryAndPersonalInfoForStaff() throws Exception {
		Long scheduleId = insertSchedule();
		insertBooking(scheduleId, "B-SHIP", "PAID", "DELIVERY", 2);
		insertBooking(scheduleId, "B-SITE", "PAID", "ONSITE", 1);

		mockMvc.perform(get(URL, scheduleId).cookie(token(AccountType.SELLER, "STAFF", MY_BUSINESS)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].ordererName").value("홍길동"))
			.andExpect(jsonPath("$.data.content[0].ordererPhone").value("010-1234-5678"))
			.andExpect(jsonPath("$.data.content[0].ordererEmail").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].delivery.recipientName").value("김받는"))
			.andExpect(jsonPath("$.data.content[0].delivery.recipientPhone").value("010-9999-0000"))
			.andExpect(jsonPath("$.data.content[0].delivery.recipientAddress").value("서울 가상구 1"))
			.andExpect(jsonPath("$.data.content[1].delivery").value((Object) null));
	}

	@Test
	@DisplayName("취소된 회차·취소된 공연·작성 중 공연의 회차도 조회된다")
	void readableWhenCanceledOrDraft() throws Exception {
		Long canceledSchedule = insertSchedule(MY_BUSINESS, "PUBLISHED", "CANCELED");
		Long canceledPerformance = insertSchedule(MY_BUSINESS, "CANCELED", "CANCELED");
		Long draft = insertSchedule(MY_BUSINESS, "DRAFT", "ON_SALE");
		insertBooking(canceledSchedule, "B-1", "PAID", "ONSITE", 1);

		call(canceledSchedule, "").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
		call(canceledPerformance, "").andExpect(status().isOk());
		call(draft, "").andExpect(status().isOk());
	}

	@Test
	@DisplayName("남의 회차와 없는 회차는 같은 404 PERFORMANCE008 이고, 남의 회차의 예매는 읽지 않는다")
	void othersAndMissingAreNotFound() throws Exception {
		Long others = insertSchedule(OTHER_BUSINESS, "PUBLISHED", "ON_SALE");
		insertBooking(others, "B-OTHER", "PAID", "ONSITE", 1);

		call(others, "")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		call(Long.MAX_VALUE, "")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE008"));
	}

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401 이다")
	void userForbiddenAndAnonymousUnauthorized() throws Exception {
		Long scheduleId = insertSchedule();

		mockMvc.perform(get(URL, scheduleId).cookie(token(AccountType.USER, "USER", null)))
			.andExpect(status().isForbidden());
		mockMvc.perform(get(URL, scheduleId)).andExpect(status().isUnauthorized());
	}
}
