package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 예매 취소(BKG-06). 예매는 실제 좌석 점유·결제 준비·결제 승인 API 로 결제 완료까지 만들고, 토스 대신 Mock 게이트웨이를 쓴다.
 * 결제 키 접두사로 Mock 의 환불 결과를 고른다. (refund_reject_ 거절, 그 밖은 성공)
 * 기본 취소 규정: 7일 전까지 0%, 6~3일 10%, 2~1일 20%, 당일 취소 불가. 좌석 154,000원, 예매 수수료 장당 2,000원.
 */
class BookingCancelApiTest extends BookingTestSupport {

	private static final int PRICE = 154000;
	private static final int BOOKING_FEE = 2000;

	@Autowired
	MockPaymentGateway gateway;

	@BeforeEach
	void resetPayments() {
		jdbc.execute("TRUNCATE TABLE payment, refund, schedule_postponement RESTART IDENTITY");
		gateway.reset();
	}

	@AfterEach
	void cleanUpPayments() {
		jdbc.execute("TRUNCATE TABLE payment, refund, schedule_postponement RESTART IDENTITY");
	}

	/** 결제 완료된 예매. bookingSeatIds 는 예매 좌석 번호이고 순서는 판매 좌석 순서와 같다. */
	private record Paid(Long bookingId, List<Long> bookingSeatIds) {
	}

	private Paid pay(User user, Show show, int seatCount, String paymentKey) throws Exception {
		MvcResult booked = book(user.session(), show.scheduleId(), show.seatIds().subList(0, seatCount))
			.andExpect(status().isCreated())
			.andReturn();
		Long bookingId = Long.parseLong(read(booked, "$.data.bookingId"));
		MvcResult prepared = mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/payments")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"))))
			.andExpect(status().isOk())
			.andReturn();
		mockMvc.perform(post("/api/v1/payments/confirm")
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"paymentKey", paymentKey,
					"orderId", read(prepared, "$.data.orderId"),
					"amount", Integer.parseInt(read(prepared, "$.data.amount"))))))
			.andExpect(status().isOk());
		List<Long> bookingSeatIds = jdbc.queryForList(
			"SELECT id FROM booking_seat WHERE booking_id = ? ORDER BY id", Long.class, bookingId);
		return new Paid(bookingId, bookingSeatIds);
	}

	private ResultActions cancel(Cookie session, Long bookingId, Object body) throws Exception {
		return mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/cancel")
			.cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String cancelForResult(Cookie session, Long bookingId, Object body) throws Exception {
		MvcResult result = cancel(session, bookingId, body).andReturn();
		return result.getResponse().getStatus() == 200 ? "OK" : read(result, "$.code");
	}

	/** 회차 시작 일시를 바꾼다. 예매는 판매 중인 회차로 만들고, 취소할 때의 남은 일수만 바꾸려는 것이다. */
	private void moveScheduleTo(Long scheduleId, LocalDateTime startAt) {
		jdbc.update("UPDATE performance_schedule SET start_at = ? WHERE id = ?", startAt, scheduleId);
	}

	private String bookingStatus(Long bookingId) {
		return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
	}

	private String paymentStatus(Long bookingId) {
		return jdbc.queryForObject("SELECT status FROM payment WHERE booking_id = ? ORDER BY id DESC LIMIT 1",
			String.class, bookingId);
	}

	// ---------------------------------------------------------------- 정상 흐름·금액

	@Test
	@DisplayName("일부 취소: 남은 일수를 날짜로 세어 수수료를 떼고, 좌석은 다시 팔 수 있게 되고, 토스에는 환불 금액과 환불 행의 멱등키가 간다")
	void cancelsPartially() throws Exception {
		Show show = show(3);
		User user = signup("first");
		Paid paid = pay(user, show, 3, "pay_key_ok");
		// 3일 뒤 00:30 시작. 남은 시간은 3일이 안 되지만 날짜로는 3일 전이라 10% 구간이다. (시간으로 세면 2일 전, 20%)
		moveScheduleTo(show.scheduleId(), LocalDate.now().plusDays(3).atTime(0, 30));

		// 상세 화면의 취소 가능 여부·수수료율은 실제 취소와 같은 계산이다
		mockMvc.perform(get("/api/v1/bookings/" + paid.bookingId()).cookie(user.session()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.cancelable").value(true))
			.andExpect(jsonPath("$.data.currentFeeRate").value(10))
			.andExpect(jsonPath("$.data.refundedAmount").value(0));

		Long canceledSeatId = paid.bookingSeatIds().get(1);
		int fee = PRICE / 10;
		int refundAmount = PRICE - fee + BOOKING_FEE;
		cancel(user.session(), paid.bookingId(), Map.of("bookingSeatIds", List.of(canceledSeatId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.bookingId").value(paid.bookingId()))
			.andExpect(jsonPath("$.data.bookingStatus").value("PARTIAL_CANCELED"))
			.andExpect(jsonPath("$.data.canceledSeatIds[0]").value(canceledSeatId))
			.andExpect(jsonPath("$.data.cancelFeeTotal").value(fee))
			.andExpect(jsonPath("$.data.refund.amount").value(refundAmount))
			.andExpect(jsonPath("$.data.refund.status").value("DONE"));

		Map<String, Object> refund = jdbc.queryForMap("SELECT * FROM refund WHERE booking_id = ?", paid.bookingId());
		assertThat(refund.get("amount")).isEqualTo(refundAmount);
		assertThat(refund.get("status")).isEqualTo("DONE");
		assertThat(refund.get("reason_type")).isEqualTo("USER_CANCEL");
		assertThat(refund.get("completed_at")).isNotNull();
		assertThat(gateway.cancelCalls()).containsExactly(
			new MockPaymentGateway.CancelCall("pay_key_ok", refundAmount, (String) refund.get("idempotency_key")));

		Map<String, Object> seat = jdbc.queryForMap("SELECT * FROM booking_seat WHERE id = ?", canceledSeatId);
		assertThat(seat.get("active")).isEqualTo(false);
		assertThat(seat.get("cancel_fee")).isEqualTo(fee);
		assertThat(seat.get("refund_id")).isEqualTo(refund.get("id"));
		assertThat(seat.get("canceled_at")).isNotNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE booking_id = ? AND active",
			Long.class, paid.bookingId())).isEqualTo(2);
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("PARTIAL_CANCELED");
		assertThat(paymentStatus(paid.bookingId())).isEqualTo("PARTIAL_CANCELED");

		// 취소한 좌석만 다시 판매 가능이고, 다른 사람이 바로 예매할 수 있다
		assertThat(seatStatus(show.seatIds().get(1))).isEqualTo("AVAILABLE");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("SOLD");
		book(signup("second").session(), show.scheduleId(), List.of(show.seatIds().get(1)))
			.andExpect(status().isCreated());
	}

	@Test
	@DisplayName("남은 좌석을 모두 취소하면 예매·결제가 전체 취소가 되고, 환불은 취소한 묶음마다 한 건씩 쌓인다")
	void cancelsRemainingSeats() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Paid paid = pay(user, show, 2, "pay_key_ok");

		cancel(user.session(), paid.bookingId(), Map.of("bookingSeatIds", List.of(paid.bookingSeatIds().get(0))))
			.andExpect(status().isOk());
		// 본문을 비우면 남은 좌석 전부를 취소한다. 7일 넘게 남아 수수료가 없다
		cancel(user.session(), paid.bookingId(), Map.of())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.bookingStatus").value("CANCELED"))
			.andExpect(jsonPath("$.data.canceledSeatIds.length()").value(1))
			.andExpect(jsonPath("$.data.cancelFeeTotal").value(0))
			.andExpect(jsonPath("$.data.refund.amount").value(PRICE + BOOKING_FEE));

		assertThat(bookingStatus(paid.bookingId())).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT canceled_at FROM booking WHERE id = ?", LocalDateTime.class,
			paid.bookingId())).isNotNull();
		assertThat(paymentStatus(paid.bookingId())).isEqualTo("CANCELED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM refund WHERE booking_id = ? AND status = 'DONE'",
			Long.class, paid.bookingId())).isEqualTo(2);
		show.seatIds().forEach(seatId -> assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE"));

		// 상세는 돌려준 금액 합계를 보이고, 더는 취소할 수 없다
		mockMvc.perform(get("/api/v1/bookings/" + paid.bookingId()).cookie(user.session()))
			.andExpect(jsonPath("$.data.refundedAmount").value(2 * (PRICE + BOOKING_FEE)))
			.andExpect(jsonPath("$.data.cancelable").value(false))
			.andExpect(jsonPath("$.data.currentFeeRate").doesNotExist());
		// 전체 취소된 예매를 다시 취소하면 409
		cancel(user.session(), paid.bookingId(), Map.of())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING008"));
	}

	@Test
	@DisplayName("연기 무료 취소 기간이면 공연 당일이어도 수수료 없이 취소된다")
	void freeCancelAfterPostponement() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Paid paid = pay(user, show, 1, "pay_key_ok");
		moveScheduleTo(show.scheduleId(), LocalDate.now().atTime(23, 59, 59));
		// 예매한 뒤에 생긴 연기라 무료 취소 대상이다
		jdbc.update("""
			INSERT INTO schedule_postponement (schedule_id, old_start_at, new_start_at, reason, free_cancel_until,
			                                   changed_by, created_at)
			VALUES (?, ?, NULL, '출연자 사정', ?, 1, ?)
			""", show.scheduleId(), now.plusDays(10), LocalDateTime.now().plusDays(14), LocalDateTime.now().plusSeconds(1));

		cancel(user.session(), paid.bookingId(), Map.of())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.cancelFeeTotal").value(0))
			.andExpect(jsonPath("$.data.refund.amount").value(PRICE + BOOKING_FEE));
	}

	// ---------------------------------------------------------------- 거절

	@Test
	@DisplayName("공연 당일에는 취소할 수 없어 400 이고 아무것도 바뀌지 않는다")
	void rejectsOnPerformanceDay() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Paid paid = pay(user, show, 1, "pay_key_ok");
		moveScheduleTo(show.scheduleId(), LocalDate.now().atTime(23, 59, 59));

		cancel(user.session(), paid.bookingId(), Map.of())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BOOKING009"));

		assertThat(bookingStatus(paid.bookingId())).isEqualTo("PAID");
		assertThat(count("refund")).isZero();
		assertThat(gateway.cancelCalls()).isEmpty();
	}

	@Test
	@DisplayName("남의 예매를 취소하면 없는 예매와 같은 404 이고, 결제 대기 예매는 409 다")
	void rejectsOthersAndUnpaidBookings() throws Exception {
		Show show = show(2);
		User owner = signup("owner");
		Paid paid = pay(owner, show, 1, "pay_key_ok");
		User other = signup("other");

		cancel(other.session(), paid.bookingId(), Map.of())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING006"));
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("PAID");

		MvcResult pending = book(other.session(), show.scheduleId(), List.of(show.seatIds().get(1)))
			.andExpect(status().isCreated())
			.andReturn();
		cancel(other.session(), Long.parseLong(read(pending, "$.data.bookingId")), Map.of())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING008"));
		assertThat(count("refund")).isZero();
	}

	// ---------------------------------------------------------------- 토스 실패

	@Test
	@DisplayName("토스가 환불을 거절해도 좌석 취소는 끝나고, 환불은 사유와 함께 실패로 남아 200 으로 알려 준다")
	void keepsSeatCancelWhenRefundFails() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Paid paid = pay(user, show, 1, "refund_reject_key");

		cancel(user.session(), paid.bookingId(), Map.of())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.bookingStatus").value("CANCELED"))
			.andExpect(jsonPath("$.data.refund.status").value("FAILED"));

		Map<String, Object> refund = jdbc.queryForMap("SELECT * FROM refund WHERE booking_id = ?", paid.bookingId());
		assertThat(refund.get("status")).isEqualTo("FAILED");
		assertThat((String) refund.get("fail_reason")).contains("NOT_CANCELABLE_PAYMENT");
		assertThat(refund.get("completed_at")).isNull();
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("CANCELED");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
		// 돌려주지 못한 금액은 환불 금액에 들어가지 않는다
		mockMvc.perform(get("/api/v1/bookings/" + paid.bookingId()).cookie(user.session()))
			.andExpect(jsonPath("$.data.refundedAmount").value(0));
	}

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("같은 좌석 취소가 동시에 여러 번 들어와도 한 번만 취소·환불된다")
	void cancelsSameSeatOnlyOnce() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Paid paid = pay(user, show, 2, "pay_key_ok");
		Map<String, Object> body = Map.of("bookingSeatIds", List.of(paid.bookingSeatIds().get(0)));

		List<String> results = runConcurrently(5, i -> () -> cancelForResult(user.session(), paid.bookingId(), body));

		assertThat(countOf("OK", results)).isEqualTo(1);
		// 나머지는 이미 취소된 좌석(BOOKING010)이거나 락을 기다리다 포기(COMMON004)한다
		assertThat(results).allMatch(result -> List.of("OK", "BOOKING010", "COMMON004").contains(result));
		assertThat(count("refund")).isEqualTo(1);
		assertThat(gateway.cancelCalls()).hasSize(1);
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("PARTIAL_CANCELED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE booking_id = ? AND active",
			Long.class, paid.bookingId())).isEqualTo(1);
	}
}
