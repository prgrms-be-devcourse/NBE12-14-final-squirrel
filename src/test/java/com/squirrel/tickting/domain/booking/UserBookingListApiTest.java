package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
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

	private void saveBookingSeat(Long bookingId, boolean active) {
		jdbc.update("""
			INSERT INTO booking_seat (booking_id, zone_name, row_label, seat_number, grade_name, price, booking_fee, active)
			VALUES (?, 'F2구역', '9열', '1', 'R석', 154000, 2000, ?)
			""", bookingId, active);
	}

	private MvcResult getList(String bearer, String query) throws Exception {
		return mockMvc.perform(get(URL + query).header("Authorization", bearer))
			.andExpect(status().isOk())
			.andReturn();
	}

	private List<String> bookingNos(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].bookingNo");
	}

	/** 응답의 일시는 초까지 찍힌다. LocalDateTime.toString() 은 0초일 때 초를 빼서 초까지 맞춰 비교한다. */
	private static String iso(LocalDateTime time) {
		return time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
	}

	// ---- 테스트 ----

	@Test
	@DisplayName("내 예매가 최신 예매순으로 나오고, 공연 제목·포스터·회차 일시·좌석 수·처음 결제 금액이 함께 온다")
	void myBookings() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		Long older = saveBooking(ME, performanceId, scheduleId, "PAID", 312000, now.minusDays(2));
		saveBookingSeat(older, true);
		saveBookingSeat(older, true);
		Long newer = saveBooking(ME, performanceId, scheduleId, "PENDING", 156000, now.minusHours(1));
		saveBookingSeat(newer, true);

		mockMvc.perform(get(URL).header("Authorization", userBearer(ME)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.content[0].bookingId").value(newer))
			.andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
			.andExpect(jsonPath("$.data.content[1].bookingId").value(older))
			.andExpect(jsonPath("$.data.content[1].bookingNo").isNotEmpty())
			.andExpect(jsonPath("$.data.content[1].status").value("PAID"))
			.andExpect(jsonPath("$.data.content[1].performanceTitle").value("가상 콘서트"))
			.andExpect(jsonPath("$.data.content[1].posterUrl").value("https://cdn.example.com/가상 콘서트.png"))
			.andExpect(jsonPath("$.data.content[1].scheduleStartAt").value(iso(now.plusDays(10))))
			.andExpect(jsonPath("$.data.content[1].seatCount").value(2))
			.andExpect(jsonPath("$.data.content[1].totalAmount").value(312000))
			.andExpect(jsonPath("$.data.content[1].createdAt").value(iso(now.minusDays(2))));
	}

	@Test
	@DisplayName("일부 취소된 예매의 좌석 수는 취소된 좌석까지 센 처음 좌석 수이고, 금액도 처음 결제 금액이다")
	void partiallyCanceledBookingKeepsOriginalCount() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		Long bookingId = saveBooking(ME, performanceId, scheduleId, "PARTIAL_CANCELED", 468000, now.minusDays(1));
		saveBookingSeat(bookingId, true);
		saveBookingSeat(bookingId, true);
		saveBookingSeat(bookingId, false);

		mockMvc.perform(get(URL).header("Authorization", userBearer(ME)))
			.andExpect(jsonPath("$.data.content[0].status").value("PARTIAL_CANCELED"))
			.andExpect(jsonPath("$.data.content[0].seatCount").value(3))
			.andExpect(jsonPath("$.data.content[0].totalAmount").value(468000));
	}

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
	@DisplayName("상태를 주지 않으면 시간 초과(EXPIRED)까지 모두 나오고, 주면 그 상태만 나온다")
	void filterByStatus() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		for (String status : List.of("PENDING", "PAID", "PARTIAL_CANCELED", "CANCELED", "EXPIRED")) {
			saveBooking(ME, performanceId, scheduleId, status, 156000, now.minusDays(1));
		}

		String body = getList(userBearer(ME), "").getResponse().getContentAsString();
		List<String> statuses = JsonPath.read(body, "$.data.content[*].status");
		assertThat(statuses).containsExactlyInAnyOrder("PENDING", "PAID", "PARTIAL_CANCELED", "CANCELED", "EXPIRED");

		mockMvc.perform(get(URL + "?status=EXPIRED").header("Authorization", userBearer(ME)))
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].status").value("EXPIRED"));
	}

	@Test
	@DisplayName("한 번에 최대 20개까지 주고, 예매 시각이 같으면 나중에 만든 예매가 먼저라 다음 페이지와 겹치지 않는다")
	void pageSizeIsCappedAndOrderIsStable() throws Exception {
		Long performanceId = savePerformance("가상 콘서트", "PUBLISHED");
		Long scheduleId = saveSchedule(performanceId, now.plusDays(10), "ON_SALE");
		for (int i = 0; i < 25; i++) {
			saveBooking(ME, performanceId, scheduleId, "PAID", 156000, now.minusDays(1));
		}

		MvcResult first = mockMvc.perform(get(URL + "?size=100").header("Authorization", userBearer(ME)))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(25))
			.andExpect(jsonPath("$.data.totalPages").value(2))
			.andReturn();
		MvcResult second = getList(userBearer(ME), "?page=1&size=100");

		assertThat(bookingNos(first)).hasSize(20).startsWith("TEST25");
		assertThat(bookingNos(second)).containsExactly("TEST5", "TEST4", "TEST3", "TEST2", "TEST1");
	}

	@Test
	@DisplayName("예매한 뒤 공연이 취소되거나 회차가 연기돼도 내역에 남고, 공연·회차 정보가 없으면 그 값만 null 이다")
	void canceledPerformanceAndMissingRows() throws Exception {
		Long canceledPerformance = savePerformance("취소된 공연", "CANCELED");
		Long postponed = saveSchedule(canceledPerformance, now.plusDays(20), "POSTPONED");
		Long withCanceled = saveBooking(ME, canceledPerformance, postponed, "CANCELED", 156000, now.minusDays(2));
		Long missing = saveBooking(ME, 999_999L, 999_999L, "PAID", 156000, now.minusDays(1));

		String body = getList(userBearer(ME), "").getResponse().getContentAsString();
		assertThat(JsonPath.<Integer>read(body, "$.data.content[0].bookingId").longValue()).isEqualTo(missing);
		assertThat(JsonPath.<Object>read(body, "$.data.content[0].performanceTitle")).isNull();
		assertThat(JsonPath.<Object>read(body, "$.data.content[0].posterUrl")).isNull();
		assertThat(JsonPath.<Object>read(body, "$.data.content[0].scheduleStartAt")).isNull();
		assertThat(JsonPath.<Integer>read(body, "$.data.content[1].bookingId").longValue()).isEqualTo(withCanceled);
		assertThat(JsonPath.<String>read(body, "$.data.content[1].performanceTitle")).isEqualTo("취소된 공연");
		assertThat(JsonPath.<String>read(body, "$.data.content[1].scheduleStartAt")).isEqualTo(iso(now.plusDays(20)));
	}

	@Test
	@DisplayName("예매가 없으면 빈 목록이다")
	void noBookings() throws Exception {
		mockMvc.perform(get(URL).header("Authorization", userBearer(ME)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(0))
			.andExpect(jsonPath("$.data.content").isEmpty());
	}

	@Test
	@DisplayName("토큰이 없으면 401, 판매자·관리자 토큰은 403 이다")
	void onlyUsers() throws Exception {
		mockMvc.perform(get(URL))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get(URL).header("Authorization", bearer(1L, AccountType.SELLER, "OWNER", 1L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
		mockMvc.perform(get(URL).header("Authorization", bearer(1L, AccountType.ADMIN, null, null)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("없는 상태 값을 보내면 400 COMMON000 이다")
	void invalidStatus() throws Exception {
		mockMvc.perform(get(URL + "?status=DONE").header("Authorization", userBearer(ME)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}
}
