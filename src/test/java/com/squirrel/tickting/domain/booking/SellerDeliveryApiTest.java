package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.infra.repository.BookingRepository;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자의 발송 처리(SLR-40~42). 시드에 없는 사업체 번호로 공연·회차·예매를 SQL 로 넣고 끝나면 지운다.
 */
class SellerDeliveryApiTest extends IntegrationTestSupport {

	private static final String TARGETS = "/api/v1/seller/performances/{performanceId}/deliveries";
	private static final String SINGLE = "/api/v1/seller/bookings/{bookingId}/delivery";
	private static final long MY_BUSINESS = 897L;
	private static final long OTHER_BUSINESS = 898L;
	private static final LocalDateTime BASE = LocalDateTime.of(2030, 1, 1, 10, 0);

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	/** 공연 하나와 회차 하나 */
	private record Show(Long performanceId, Long scheduleId) {
	}

	@AfterEach
	void deleteInsertedRows() {
		String businesses = "(" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		String performances = "SELECT id FROM performance WHERE business_id IN " + businesses;
		jdbc.update("DELETE FROM booking_seat WHERE booking_id IN (SELECT id FROM booking WHERE business_id IN "
			+ businesses + ")");
		jdbc.update("DELETE FROM booking WHERE business_id IN " + businesses);
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + performances + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN " + businesses);
	}

	private Show show(long businessId) {
		Long policyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		Long venueId = jdbc.queryForObject("SELECT id FROM venue ORDER BY id LIMIT 1", Long.class);
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '발송 테스트 공연', 'CONCERT', 0, 120, 'https://cdn.example.com/poster.jpg', 'PUBLISHED',
			        NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId);
		return new Show(performanceId, schedule(performanceId, BASE.plusDays(100)));
	}

	private Long schedule(Long performanceId, LocalDateTime startAt) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, 'ON_SALE', NOW(), NOW())
			RETURNING id
			""", Long.class, performanceId, startAt);
	}

	private Long booking(Show show, String no, String status, String receiveType) {
		return booking(show.performanceId(), show.scheduleId(), no, status, receiveType);
	}

	private Long booking(Long performanceId, Long scheduleId, String no, String status, String receiveType) {
		boolean delivery = "DELIVERY".equals(receiveType);
		return jdbc.queryForObject("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, recipient_name, recipient_phone,
			                     recipient_address, ticket_amount, booking_fee_amount, total_amount,
			                     cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, 1, ?, ?, (SELECT business_id FROM performance WHERE id = ?), ?, ?, '홍길동', '010-1234-5678',
			        'hong@example.com', ?, ?, ?, 0, 0, 0, '[]'::jsonb, ?, ?, ?)
			RETURNING id
			""", Long.class, no, scheduleId, performanceId, performanceId, status, receiveType,
			delivery ? "김받는" : null, delivery ? "010-9999-0000" : null, delivery ? "서울 가상구 1" : null,
			BASE, BASE, BASE);
	}

	private void seat(Long bookingId, boolean active) {
		jdbc.update("""
			INSERT INTO booking_seat (booking_id, zone_name, row_label, seat_number, grade_name, price, booking_fee,
			                          active)
			VALUES (?, 'F2구역', '9열', ?, 'R', 1000, 0, ?)
			""", bookingId, String.valueOf(System.nanoTime()), active);
	}

	private Cookie seller(long businessId) {
		return sessionCookie(new CustomUserDetails(1L, AccountType.SELLER, "STAFF", businessId));
	}

	private ResultActions ship(Long bookingId, String carrier, String trackingNo) throws Exception {
		return mockMvc.perform(put(SINGLE, bookingId)
			.cookie(seller(MY_BUSINESS))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("carrier", carrier, "trackingNo", trackingNo))));
	}

	private static Map<String, String> item(String bookingNo, String carrier, String trackingNo) {
		return Map.of("bookingNo", bookingNo, "carrier", carrier, "trackingNo", trackingNo);
	}

	private ResultActions shipAll(Long performanceId, List<Map<String, String>> items) throws Exception {
		return mockMvc.perform(put(TARGETS, performanceId)
			.cookie(seller(MY_BUSINESS))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("items", items))));
	}

	private String trackingNo(Long bookingId) {
		return jdbc.queryForObject("SELECT tracking_no FROM booking WHERE id = ?", String.class, bookingId);
	}

	private LocalDateTime shippedAt(Long bookingId) {
		return jdbc.queryForObject("SELECT shipped_at FROM booking WHERE id = ?", LocalDateTime.class, bookingId);
	}

	@Test
	@DisplayName("송장을 다시 입력하면 택배사·송장번호는 바뀌고 발송 일시는 처음 값이 남는다")
	void shipTwiceKeepsFirstShippedAt() throws Exception {
		Long bookingId = booking(show(MY_BUSINESS), "D-1", "PARTIAL_CANCELED", "DELIVERY");

		ship(bookingId, "CJ", "1111").andExpect(status().isOk())
			.andExpect(jsonPath("$.data.delivery.carrier").value("CJ"))
			.andExpect(jsonPath("$.data.delivery.shippedAt").isNotEmpty());
		LocalDateTime first = LocalDateTime.of(2029, 1, 1, 0, 0);
		jdbc.update("UPDATE booking SET shipped_at = ? WHERE id = ?", first, bookingId);

		ship(bookingId, "HANJIN", "2222-A").andExpect(status().isOk())
			.andExpect(jsonPath("$.data.delivery.carrier").value("HANJIN"))
			.andExpect(jsonPath("$.data.delivery.trackingNo").value("2222-A"));

		assertThat(trackingNo(bookingId)).isEqualTo("2222-A");
		assertThat(shippedAt(bookingId)).isEqualTo(first);
	}

	@Test
	@DisplayName("남의 예매는 404, 현장 수령은 409(BOOKING014), 발송할 수 없는 상태는 409(BOOKING015)이고 송장은 남지 않는다")
	void singleRejections() throws Exception {
		Show mine = show(MY_BUSINESS);
		Long others = booking(show(OTHER_BUSINESS), "D-OTHER", "PAID", "DELIVERY");
		Long onsite = booking(mine, "D-ONSITE", "PAID", "ONSITE");
		Long canceled = booking(mine, "D-CANCEL", "CANCELED", "DELIVERY");
		Long pending = booking(mine, "D-PENDING", "PENDING", "DELIVERY");

		ship(others, "CJ", "1111").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOKING006"));
		ship(onsite, "CJ", "1111").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING014"));
		ship(canceled, "CJ", "1111").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING015"));
		ship(pending, "CJ", "1111").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING015"));

		assertThat(List.of(others, onsite, canceled, pending)).allSatisfy(id -> assertThat(trackingNo(id)).isNull());
	}

	@Test
	@DisplayName("일괄 입력은 가능한 행만 반영하고, 안 되는 행은 위치와 이유를 알려 준다. 같은 파일을 다시 올려도 발송 일시는 그대로다")
	void bulkAppliesOnlyShippableRows() throws Exception {
		Show mine = show(MY_BUSINESS);
		Long paid = booking(mine, "D-PAID", "PAID", "DELIVERY");
		Long canceled = booking(mine, "D-CANCEL", "CANCELED", "DELIVERY");
		Long onsite = booking(mine, "D-ONSITE", "PAID", "ONSITE");
		Long anotherPerformance = booking(show(MY_BUSINESS), "D-ANOTHER", "PAID", "DELIVERY");
		Long others = booking(show(OTHER_BUSINESS), "D-OTHER", "PAID", "DELIVERY");
		Long partial = booking(mine, "D-PART", "PARTIAL_CANCELED", "DELIVERY");
		List<Map<String, String>> items = List.of(
			item("D-CANCEL", "CJ", "10"),
			item("D-PAID", "CJ", "11"),
			item("D-ONSITE", "CJ", "12"),
			item("D-ANOTHER", "CJ", "13"),
			item("D-OTHER", "CJ", "14"),
			item("D-NONE", "CJ", "15"),
			item("D-PART", "LOGEN", "16"));

		shipAll(mine.performanceId(), items).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.total").value(7))
			.andExpect(jsonPath("$.data.succeeded").value(2))
			.andExpect(jsonPath("$.data.failed[*].row").value(contains(0, 2, 3, 4, 5)))
			.andExpect(jsonPath("$.data.failed[*].code").value(contains(
				"BOOKING015", "BOOKING014", "BOOKING006", "BOOKING006", "BOOKING006")));

		assertThat(trackingNo(paid)).isEqualTo("11");
		assertThat(trackingNo(partial)).isEqualTo("16");
		assertThat(List.of(canceled, onsite, anotherPerformance, others))
			.allSatisfy(id -> assertThat(trackingNo(id)).isNull());

		LocalDateTime first = shippedAt(paid);
		shipAll(mine.performanceId(), List.of(item("D-PAID", "CJ", "99"))).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.succeeded").value(1));
		assertThat(trackingNo(paid)).isEqualTo("99");
		assertThat(shippedAt(paid)).isEqualTo(first);
	}

	@Test
	@DisplayName("모르는 택배사·같은 예매 번호 두 번·형식 오류가 한 행이라도 있으면 전체를 거절하고 아무것도 바꾸지 않는다")
	void bulkRejectsWholeRequest() throws Exception {
		Show mine = show(MY_BUSINESS);
		Long first = booking(mine, "D-1", "PAID", "DELIVERY");
		booking(mine, "D-2", "PAID", "DELIVERY");
		Map<String, String> good = item("D-1", "CJ", "11");

		for (Map<String, String> bad : List.of(item("D-2", "cj", "12"), item("D-1", "CJ", "12"),
			item("D-2", "CJ", "12 34"))) {
			shipAll(mine.performanceId(), List.of(good, bad)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON000"))
				.andExpect(jsonPath("$.message").value(containsString("items[1]")));
		}

		assertThat(trackingNo(first)).isNull();
	}

	@Test
	@DisplayName("남의 공연의 배송 대상 조회와 일괄 입력은 404 이고 송장이 들어가지 않는다")
	void othersPerformanceIsNotFound() throws Exception {
		Show other = show(OTHER_BUSINESS);
		Long bookingId = booking(other, "D-OTHER", "PAID", "DELIVERY");

		mockMvc.perform(get(TARGETS, other.performanceId()).cookie(seller(MY_BUSINESS)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		shipAll(other.performanceId(), List.of(item("D-OTHER", "CJ", "11")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));

		assertThat(trackingNo(bookingId)).isNull();
	}

	@Test
	@DisplayName("배송 대상은 발송할 수 있는 예매만 회차 순으로 주고, 발송 전·후와 회차로 거른다")
	void targets() throws Exception {
		Show mine = show(MY_BUSINESS);
		Long earlier = schedule(mine.performanceId(), BASE.plusDays(50));
		Long later = booking(mine, "D-LATER", "PAID", "DELIVERY");
		seat(later, true);
		seat(later, false);
		booking(mine.performanceId(), earlier, "D-EARLIER", "PARTIAL_CANCELED", "DELIVERY");
		booking(mine, "D-ONSITE", "PAID", "ONSITE");
		booking(mine, "D-CANCEL", "CANCELED", "DELIVERY");
		booking(mine, "D-PENDING", "PENDING", "DELIVERY");
		Long sent = booking(mine, "D-SENT", "PAID", "DELIVERY");
		ship(sent, "CJ", "11").andExpect(status().isOk());

		mockMvc.perform(get(TARGETS, mine.performanceId()).cookie(seller(MY_BUSINESS)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[*].bookingNo").value(
				contains("D-EARLIER", "D-LATER")))
			.andExpect(jsonPath("$.data[1].seatCount").value(1))
			.andExpect(jsonPath("$.data[1].recipientAddress").value("서울 가상구 1"));
		mockMvc.perform(get(TARGETS + "?shipped=true", mine.performanceId()).cookie(seller(MY_BUSINESS)))
			.andExpect(jsonPath("$.data[*].bookingNo").value(contains("D-SENT")));
		mockMvc.perform(get(TARGETS + "?scheduleId=" + earlier, mine.performanceId()).cookie(seller(MY_BUSINESS)))
			.andExpect(jsonPath("$.data[*].bookingNo").value(contains("D-EARLIER")));
	}

	@Test
	@DisplayName("조회 뒤에 취소된 예매는 조건부 UPDATE 가 건드리지 않는다")
	void updateSkipsCanceledBooking() {
		Show mine = show(MY_BUSINESS);
		Long canceled = booking(mine, "D-CANCEL", "CANCELED", "DELIVERY");

		transactionTemplate.executeWithoutResult(tx -> {
			assertThat(bookingRepository.ship(canceled, MY_BUSINESS, "CJ", "11", BASE)).isZero();
			assertThat(bookingRepository.shipAll(mine.performanceId(), MY_BUSINESS, new String[] {"D-CANCEL"},
				new String[] {"CJ"}, new String[] {"11"}, BASE)).isEmpty();
		});

		assertThat(trackingNo(canceled)).isNull();
	}

	@Test
	@DisplayName("DB 는 반쪽 송장과 현장 수령 예매의 송장을 거절한다")
	void checkConstraint() {
		Show mine = show(MY_BUSINESS);
		Long delivery = booking(mine, "D-1", "PAID", "DELIVERY");
		Long onsite = booking(mine, "D-2", "PAID", "ONSITE");

		assertThatThrownBy(() -> jdbc.update("UPDATE booking SET carrier = 'CJ' WHERE id = ?", delivery))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE booking SET carrier = 'CJ', tracking_no = '1' WHERE id = ?",
			delivery)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
			"UPDATE booking SET carrier = 'CJ', tracking_no = '1', shipped_at = NOW() WHERE id = ?", onsite))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
