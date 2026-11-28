package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 회차 연기(SLR-28)·새 일정 확정(SLR-29)과 그에 따라 달라지는 점유·결제·무료 취소.
 * 예매·결제는 실제 API 로 만들고 토스 대신 Mock 게이트웨이를 쓴다. 대기열은 QueueApiTest, 탈퇴는 UserWithdrawApiTest 가 본다.
 */
class SellerSchedulePostponeApiTest extends BookingTestSupport {

	/** BookingTestSupport 가 공연을 넣는 사업체 번호 */
	private static final long MY_BUSINESS = 1L;
	private static final long OTHER_BUSINESS = 986L;
	private static final long SELLER_ID = 55L;
	private static final int PRICE = 154000;

	@Autowired
	private StringRedisTemplate redis;

	@Autowired
	private MockPaymentGateway gateway;

	@BeforeEach
	@AfterEach
	void clearPaymentsAndPostponements() {
		jdbc.execute("TRUNCATE TABLE payment, refund, schedule_postponement RESTART IDENTITY");
		gateway.reset();
	}

	@Test
	@DisplayName("일정 미정 연기: 점유와 연기 전 예매의 결제 준비·승인이 BOOKING013 이고, 확정하면 판매 중이 되어 새로 점유되지만 연기 전 예매는 계속 결제할 수 없다")
	void postponesWithoutDateAndConfirms() throws Exception {
		Show show = postponableShow(3);
		insertSellerBusiness();
		User before = signup("before");
		Long pending = bookingId(before, show, 0, 1);
		MvcResult prepared = prepare(before, pending).andExpect(status().isOk()).andReturn();
		jdbc.update("UPDATE performance_schedule SET status = 'PAUSED', pause_reason = '점검' WHERE id = ?",
			show.scheduleId());
		String cacheKey = "tickting:cache:performance-detail:" + show.performanceId();
		redis.opsForValue().set(cacheKey, "{}");

		postpone(show.scheduleId(), Map.of("reason", "  출연자 사정  "))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("POSTPONED"))
			.andExpect(jsonPath("$.data.postponement.newStartAt").value(nullValue()))
			.andExpect(jsonPath("$.data.postponement.freeCancelUntil").value(nullValue()));

		assertThat(redis.hasKey(cacheKey)).isFalse();
		assertThat(schedule(show.scheduleId())).isEqualTo("POSTPONED:null");
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM schedule_postponement
			WHERE new_start_at IS NULL AND free_cancel_until IS NOT NULL AND reason = '출연자 사정' AND changed_by = ?
			""", Long.class, SELLER_ID)).isEqualTo(1);
		assertThat(auditActions()).containsExactly("POSTPONE");
		book(signup("blocked").session(), show.scheduleId(), List.of(show.seatIds().get(1)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING013"));
		prepare(before, pending).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING013"));
		confirmPayment(before, prepared).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING013"));
		assertThat(gateway.confirmedOrderIds()).isEmpty();
		mockMvc.perform(get("/api/v1/performances").param("keyword", "가상 콘서트"))
			.andExpect(jsonPath("$.data.content[0].saleStatus").value("POSTPONED"));
		mockMvc.perform(get("/api/v1/performances/" + show.performanceId()))
			.andExpect(jsonPath("$.data.schedules[0].saleStatus").value("POSTPONED"))
			.andExpect(jsonPath("$.data.schedules[0].postponement.reason").value("출연자 사정"));
		postpone(show.scheduleId(), Map.of("reason", "다시"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE020"));

		LocalDateTime newStartAt = minute(60);
		confirm(show.scheduleId(), Map.of("newStartAt", format(newStartAt)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ON_SALE"))
			.andExpect(jsonPath("$.data.postponement.freeCancelUntil").isNotEmpty());

		assertThat(schedule(show.scheduleId())).isEqualTo("ON_SALE:null");
		assertThat(startAt(show.scheduleId())).isEqualTo(newStartAt);
		assertThat(count("schedule_postponement")).isEqualTo(1);
		assertThat(newStartAtOfHistory()).isEqualTo(newStartAt);
		assertThat(auditActions()).containsExactly("POSTPONE", "POSTPONE_CONFIRM");
		book(signup("after").session(), show.scheduleId(), List.of(show.seatIds().get(2)))
			.andExpect(status().isCreated());
		prepare(before, pending).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING013"));
		confirm(show.scheduleId(), Map.of("newStartAt", format(newStartAt)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE021"));
	}

	@Test
	@DisplayName("날짜를 바꾼 연기: 상태는 그대로이고, 연기 전 예매만 마감까지 수수료 없이 취소되며 마감 뒤에는 새 일시 기준 규정이다")
	void postponesWithDate() throws Exception {
		Show show = postponableShow(4);
		User paidBefore = signup("paid");
		Long paid = pay(paidBefore, show, 0, 2);
		User pendingUser = signup("pending");
		Long pending = bookingId(pendingUser, show, 2, 1);
		jdbc.update("UPDATE performance_schedule SET status = 'PAUSED', pause_reason = '점검' WHERE id = ?",
			show.scheduleId());
		LocalDateTime newStartAt = minute(30);
		LocalDateTime deadline = minute(10);

		postpone(show.scheduleId(), Map.of("reason", "공연장 사정", "newStartAt", format(newStartAt),
			"freeCancelUntil", format(deadline)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("PAUSED"));

		assertThat(schedule(show.scheduleId())).isEqualTo("PAUSED:점검");
		assertThat(startAt(show.scheduleId())).isEqualTo(newStartAt);
		// 중지 중이어도, 재개해도 연기 전 예매는 결제할 수 없다. "중지"로 알리면 재개를 기다리게 된다.
		prepare(pendingUser, pending).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING013"));
		jdbc.update("UPDATE performance_schedule SET status = 'ON_SALE', pause_reason = NULL WHERE id = ?",
			show.scheduleId());
		prepare(pendingUser, pending).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING013"));
		User bookedAfter = signup("after");
		Long after = pay(bookedAfter, show, 3, 1);

		// 3일 뒤로 당겨 두면 규정으로는 10% 구간이다. 마감 전이라 연기 전 예매만 0% 다.
		moveScheduleTo(show.scheduleId(), minute(3));
		mockMvc.perform(get("/api/v1/bookings/" + after).cookie(bookedAfter.session()))
			.andExpect(jsonPath("$.data.currentFeeRate").value(10))
			.andExpect(jsonPath("$.data.postponeFreeCancel").value(nullValue()));
		mockMvc.perform(get("/api/v1/bookings/" + paid).cookie(paidBefore.session()))
			.andExpect(jsonPath("$.data.currentFeeRate").value(0))
			.andExpect(jsonPath("$.data.postponeFreeCancel.until").isNotEmpty());
		Long firstSeat = bookingSeatIds(paid).getFirst();
		cancel(paidBefore, paid, Map.of("bookingSeatIds", List.of(firstSeat)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.cancelFeeTotal").value(0));

		jdbc.update("UPDATE schedule_postponement SET free_cancel_until = ?", LocalDateTime.now().minusMinutes(1));
		cancel(paidBefore, paid, Map.of())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.cancelFeeTotal").value(PRICE / 10));
		assertThat(jdbc.queryForList("SELECT reason_type FROM refund ORDER BY id", String.class))
			.containsExactly("POSTPONE_CANCEL", "USER_CANCEL");
	}

	@Test
	@DisplayName("일정 미정인 동안은 원래 일시와 저장한 마감이 지나도 연기 전 예매가 수수료 없이 취소되고, 원래 일시가 지난 회차도 확정할 수 있다")
	void keepsFreeCancelWhileUndecided() throws Exception {
		Show show = postponableShow(1);
		User user = signup("first");
		Long paid = pay(user, show, 0, 1);
		postpone(show.scheduleId(), Map.of("reason", "출연자 사정")).andExpect(status().isOk());
		moveScheduleTo(show.scheduleId(), now.minusDays(20));
		jdbc.update("UPDATE schedule_postponement SET free_cancel_until = ?", now.minusDays(6));

		mockMvc.perform(get("/api/v1/bookings/" + paid).cookie(user.session()))
			.andExpect(jsonPath("$.data.cancelable").value(true))
			.andExpect(jsonPath("$.data.postponeFreeCancel").isNotEmpty())
			.andExpect(jsonPath("$.data.postponeFreeCancel.until").value(nullValue()));
		mockMvc.perform(get("/api/v1/bookings").cookie(user.session()))
			.andExpect(jsonPath("$.data.content[0].scheduleStatus").value("POSTPONED"));
		cancel(user, paid, Map.of())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.cancelFeeTotal").value(0));

		assertThat(jdbc.queryForObject("SELECT reason_type FROM refund", String.class)).isEqualTo("POSTPONE_CANCEL");
		assertThat(seatStatus(show.seatIds().getFirst())).isEqualTo("AVAILABLE");
		confirm(show.scheduleId(), Map.of("newStartAt", format(minute(30)))).andExpect(status().isOk());
	}

	@Test
	@DisplayName("무료 취소 마감은 새 일시 이하, 7일 뒤와 남은 이전 마감 이상이어야 하고, 비우면 이전 마감을 줄이지 않으며 새 일시를 넘지 않는다")
	void boundsFreeCancelDeadline() throws Exception {
		Show show = postponableShow(1);
		Long scheduleId = show.scheduleId();
		LocalDateTime first = minute(60);

		postpone(scheduleId, Map.of("reason", "사정", "freeCancelUntil", format(minute(10))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
		assertDeadlineRejected(scheduleId, first, first.plusMinutes(1));
		assertDeadlineRejected(scheduleId, first, minute(6));
		assertThat(count("schedule_postponement")).isZero();
		assertThat(startAt(scheduleId)).isNotEqualTo(first);

		LocalDateTime longDeadline = minute(40);
		postpone(scheduleId, Map.of("reason", "사정", "newStartAt", format(first), "freeCancelUntil", format(longDeadline)))
			.andExpect(status().isOk());
		postpone(scheduleId, Map.of("reason", "사정", "newStartAt", format(first)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PERFORMANCE023"));
		assertDeadlineRejected(scheduleId, minute(70), minute(30));

		// 비우면 14일 뒤가 아니라 남은 이전 마감(40일 뒤)이다.
		postpone(scheduleId, Map.of("reason", "사정", "newStartAt", format(minute(70)))).andExpect(status().isOk());
		assertThat(latestDeadline()).isEqualTo(longDeadline);
		// 이전 마감보다 이른 일시로 앞당기면 새 일시가 마감이다.
		LocalDateTime earlier = minute(20);
		postpone(scheduleId, Map.of("reason", "사정", "newStartAt", format(earlier))).andExpect(status().isOk());
		assertThat(latestDeadline()).isEqualTo(earlier);
	}

	@Test
	@DisplayName("남의 회차는 404, 작성 중 공연·시작한 회차·같은 공연장 겹침은 거절되고 아무것도 바뀌지 않는다")
	void rejectsWithoutChanging() throws Exception {
		Show show = postponableShow(1);
		Show other = postponableShow(1);
		jdbc.update("UPDATE performance_schedule SET start_at = ? WHERE id = ?", minute(50), other.scheduleId());
		Map<String, Object> undecided = Map.of("reason", "사정");

		send(OTHER_BUSINESS, show.scheduleId(), "postpone", undecided)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		send(OTHER_BUSINESS, show.scheduleId(), "postpone/confirm", Map.of("newStartAt", format(minute(30))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		postpone(show.scheduleId(), Map.of("reason", "사정", "newStartAt", format(minute(50))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE007"));

		jdbc.update("UPDATE performance SET status = 'DRAFT' WHERE id = ?", show.performanceId());
		postpone(show.scheduleId(), undecided)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE019"));
		jdbc.update("UPDATE performance SET status = 'PUBLISHED' WHERE id = ?", show.performanceId());
		moveScheduleTo(show.scheduleId(), now.minusMinutes(1));
		postpone(show.scheduleId(), undecided)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PERFORMANCE502"));

		assertThat(count("schedule_postponement")).isZero();
		assertThat(count("audit_log")).isZero();
		assertThat(schedule(show.scheduleId())).isEqualTo("ON_SALE:null");
	}

	@Test
	@DisplayName("같은 회차를 동시에 연기하면 하나만 성공하고 연기 이력과 감사 로그는 한 줄이다")
	void concurrentPostponeSavesOnce() throws Exception {
		Show show = postponableShow(1);

		List<Integer> statuses = runConcurrently(4, i -> () ->
			postpone(show.scheduleId(), Map.of("reason", "사정")).andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnlyOnce(200).containsOnly(200, 409);
		assertThat(count("schedule_postponement")).isEqualTo(1);
		assertThat(count("audit_log")).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 데이터·호출 도우미

	/** 판매 종료를 내일로 둔 공연. 새 일시가 판매 종료보다 이르면 PERFORMANCE408 이라 앞당겨 둔다. */
	private Show postponableShow(int seatCount) {
		Show show = show(seatCount);
		jdbc.update("UPDATE performance SET sale_close_at = ? WHERE id = ?", now.plusDays(1), show.performanceId());
		return show;
	}

	/** 지금부터 days 일 뒤. 계산한 마감이 분 단위라 분 아래를 자른다. */
	private LocalDateTime minute(int days) {
		return now.truncatedTo(ChronoUnit.MINUTES).plusDays(days);
	}

	private static String format(LocalDateTime time) {
		return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").format(time);
	}

	/** 공연 상세는 사업체 정보를 함께 준다. 테이블을 비우고 시작하므로 번호가 MY_BUSINESS 인 행이 생긴다. */
	private void insertSellerBusiness() {
		jdbc.update("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone, contact_email,
			                             terms_agreed_at, status, created_at, updated_at)
			VALUES ('(주)가상기획', '김대표', '1234567890', '01012345678', 'contact@company.com', ?, 'APPROVED', ?, ?)
			""", now, now, now);
	}

	private void moveScheduleTo(Long scheduleId, LocalDateTime startAt) {
		jdbc.update("UPDATE performance_schedule SET start_at = ? WHERE id = ?", startAt, scheduleId);
	}

	/** "상태:중지 사유" */
	private String schedule(Long scheduleId) {
		return jdbc.queryForObject(
			"SELECT status || ':' || COALESCE(pause_reason, 'null') FROM performance_schedule WHERE id = ?",
			String.class, scheduleId);
	}

	private LocalDateTime startAt(Long scheduleId) {
		return jdbc.queryForObject("SELECT start_at FROM performance_schedule WHERE id = ?", LocalDateTime.class,
			scheduleId);
	}

	private LocalDateTime newStartAtOfHistory() {
		return jdbc.queryForObject("SELECT new_start_at FROM schedule_postponement", LocalDateTime.class);
	}

	private LocalDateTime latestDeadline() {
		return jdbc.queryForObject("SELECT free_cancel_until FROM schedule_postponement ORDER BY id DESC LIMIT 1",
			LocalDateTime.class);
	}

	private List<String> auditActions() {
		return jdbc.queryForList("SELECT action FROM audit_log WHERE target_type = 'SCHEDULE' ORDER BY id",
			String.class);
	}

	private void assertDeadlineRejected(Long scheduleId, LocalDateTime newStartAt, LocalDateTime deadline)
		throws Exception {
		postpone(scheduleId, Map.of("reason", "사정", "newStartAt", format(newStartAt),
			"freeCancelUntil", format(deadline)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PERFORMANCE022"));
	}

	private ResultActions postpone(Long scheduleId, Map<String, Object> body) throws Exception {
		return send(MY_BUSINESS, scheduleId, "postpone", body);
	}

	private ResultActions confirm(Long scheduleId, Map<String, Object> body) throws Exception {
		return send(MY_BUSINESS, scheduleId, "postpone/confirm", body);
	}

	/** 담당자 토큰으로 보낸다. */
	private ResultActions send(long businessId, Long scheduleId, String action, Map<String, Object> body)
		throws Exception {
		return postAs(sessionCookie(new CustomUserDetails(SELLER_ID, AccountType.SELLER, "STAFF", businessId)),
			"/api/v1/seller/schedules/" + scheduleId + "/" + action, body);
	}

	/** 결제 대기 예매. seatIndex 번째 좌석부터 seatCount 장을 잡는다. */
	private Long bookingId(User user, Show show, int seatIndex, int seatCount) throws Exception {
		MvcResult booked = book(user.session(), show.scheduleId(),
			show.seatIds().subList(seatIndex, seatIndex + seatCount))
			.andExpect(status().isCreated())
			.andReturn();
		return Long.parseLong(read(booked, "$.data.bookingId"));
	}

	/** 결제 완료된 예매. */
	private Long pay(User user, Show show, int seatIndex, int seatCount) throws Exception {
		Long bookingId = bookingId(user, show, seatIndex, seatCount);
		confirmPayment(user, prepare(user, bookingId).andExpect(status().isOk()).andReturn())
			.andExpect(status().isOk());
		return bookingId;
	}

	private ResultActions prepare(User user, Long bookingId) throws Exception {
		return postAs(user.session(), "/api/v1/bookings/" + bookingId + "/payments", Map.of("receiveType", "ONSITE"));
	}

	private ResultActions confirmPayment(User user, MvcResult prepared) throws Exception {
		String orderId = read(prepared, "$.data.orderId");
		return postAs(user.session(), "/api/v1/payments/confirm", Map.of(
			"paymentKey", "pay_" + orderId,
			"orderId", orderId,
			"amount", Integer.parseInt(read(prepared, "$.data.amount"))));
	}

	private ResultActions cancel(User user, Long bookingId, Map<String, Object> body) throws Exception {
		return postAs(user.session(), "/api/v1/bookings/" + bookingId + "/cancel", body);
	}

	private List<Long> bookingSeatIds(Long bookingId) {
		return jdbc.queryForList("SELECT id FROM booking_seat WHERE booking_id = ? ORDER BY id", Long.class, bookingId);
	}

	private ResultActions postAs(Cookie session, String url, Object body) throws Exception {
		return mockMvc.perform(post(url)
			.cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}
}
