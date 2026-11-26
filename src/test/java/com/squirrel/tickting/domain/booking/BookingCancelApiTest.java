package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.facade.BookingCancelFacade;
import com.squirrel.tickting.domain.booking.service.BookingCancelPlan;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.payment.facade.RefundRetryFacade;
import com.squirrel.tickting.domain.payment.gateway.MockPaymentGateway;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.service.PaymentService;
import com.squirrel.tickting.domain.payment.service.RefundService;
import com.squirrel.tickting.domain.payment.type.RefundReasonType;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예매 취소(BKG-06). 예매는 실제 좌석 점유·결제 준비·결제 승인 API 로 결제 완료까지 만들고, 토스 대신 Mock 게이트웨이를 쓴다.
 * 결제 키 접두사로 Mock 의 환불 결과를 고른다. (refund_reject_ 거절, 그 밖은 성공)
 * 기본 취소 규정: 7일 전까지 0%, 6~3일 10%, 2~1일 20%, 당일 취소 불가. 좌석 154,000원, 예매 수수료 장당 2,000원.
 * 판매자의 회차 취소(SLR-15)가 예매·결제·환불에 미치는 영향도 여기서 확인한다. 스케줄러는 꺼 두고 파사드를 직접 부른다.
 * 대상은 한 건씩 끊어 가져온다. (IntegrationTestSupport)
 */
class BookingCancelApiTest extends BookingTestSupport {

	private static final int PRICE = 154000;
	private static final int BOOKING_FEE = 2000;

	@Autowired
	MockPaymentGateway gateway;

	@Autowired
	BookingCancelFacade bookingCancelFacade;

	@Autowired
	RefundRetryFacade refundRetryFacade;

	@Autowired
	BookingService bookingService;

	@Autowired
	PaymentService paymentService;

	@Autowired
	RefundService refundService;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	RedissonClient redissonClient;

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

	// ---------------------------------------------------------------- 회차 취소: 결제·직접 취소 차단

	/** 판매자가 회차를 취소한 상태로 만든다. 취소 API 자체는 SellerScheduleCancelApiTest 가 확인한다. */
	private void cancelSchedule(Long scheduleId) {
		jdbc.update("UPDATE performance_schedule SET status = 'CANCELED', cancel_reason = '출연자 사정' WHERE id = ?",
			scheduleId);
	}

	@Test
	@DisplayName("취소된 회차의 결제 대기 예매는 결제 준비와 승인이 409 BOOKING007 이고, 토스를 부르지 않으며 결제는 준비로 남는다")
	void blocksPaymentOfCanceledSchedule() throws Exception {
		Show show = show(1);
		User user = signup("first");
		MvcResult booked = book(user.session(), show.scheduleId(), show.seatIds()).andReturn();
		Long bookingId = Long.parseLong(read(booked, "$.data.bookingId"));
		String url = "/api/v1/bookings/" + bookingId + "/payments";
		String receive = objectMapper.writeValueAsString(Map.of("receiveType", "ONSITE"));
		MvcResult prepared = mockMvc.perform(post(url).cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON).content(receive))
			.andExpect(status().isOk())
			.andReturn();
		cancelSchedule(show.scheduleId());

		mockMvc.perform(post(url).cookie(user.session()).contentType(MediaType.APPLICATION_JSON).content(receive))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING007"))
			.andExpect(jsonPath("$.message").value("취소된 회차라 결제할 수 없습니다."));
		mockMvc.perform(post("/api/v1/payments/confirm").cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of(
					"paymentKey", "pay_key_ok",
					"orderId", read(prepared, "$.data.orderId"),
					"amount", Integer.parseInt(read(prepared, "$.data.amount"))))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING007"));

		assertThat(gateway.confirmedOrderIds()).isEmpty();
		assertThat(paymentStatus(bookingId)).isEqualTo("READY");
		assertThat(bookingStatus(bookingId)).isEqualTo("PENDING");
	}

	@Test
	@DisplayName("연기 무료 취소 대상이었어도 회차가 취소되면 직접 취소는 409 BOOKING008 이고 상세는 취소 불가다")
	void blocksUserCancelOfCanceledSchedule() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Paid paid = pay(user, show, 2, "pay_key_ok");
		jdbc.update("""
			INSERT INTO schedule_postponement (schedule_id, old_start_at, new_start_at, reason, free_cancel_until,
			                                   changed_by, created_at)
			VALUES (?, ?, NULL, '출연자 사정', ?, 1, ?)
			""", show.scheduleId(), now.plusDays(10), LocalDateTime.now().plusDays(14), LocalDateTime.now().plusSeconds(1));
		cancelSchedule(show.scheduleId());

		cancel(user.session(), paid.bookingId(), Map.of("bookingSeatIds", List.of(paid.bookingSeatIds().get(0))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING008"))
			.andExpect(jsonPath("$.message").value("취소된 회차입니다. 환불은 자동으로 처리됩니다."));
		mockMvc.perform(get("/api/v1/bookings/" + paid.bookingId()).cookie(user.session()))
			.andExpect(jsonPath("$.data.cancelable").value(false));

		assertThat(count("refund")).isZero();
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("PAID");
	}

	// ---------------------------------------------------------------- 회차 취소: 환불 만들기와 예매 반영

	private Map<String, Object> scheduleRefund(Long bookingId) {
		return jdbc.queryForMap(
			"SELECT * FROM refund WHERE booking_id = ? AND reason_type = 'PERFORMANCE_CANCEL'", bookingId);
	}

	private long activeSeatCount(Long bookingId) {
		return jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE booking_id = ? AND active", Long.class,
			bookingId);
	}

	@Test
	@DisplayName("판매자가 회차를 취소하면 남은 좌석만큼 수수료 없는 환불을 만들고, 토스 환불이 끝난 뒤에야 예매를 취소하고 좌석을 되돌린다")
	void refundsCanceledScheduleThenCancelsBooking() throws Exception {
		Show show = show(3);
		User user = signup("first");
		Paid paid = pay(user, show, 3, "pay_key_ok");
		// 3일 전이라 직접 취소한 좌석에는 10% 수수료가 붙는다. 이 수수료는 회차를 취소해도 돌려주지 않는다.
		moveScheduleTo(show.scheduleId(), LocalDate.now().plusDays(3).atTime(12, 0));
		cancel(user.session(), paid.bookingId(), Map.of("bookingSeatIds", List.of(paid.bookingSeatIds().get(0))))
			.andExpect(status().isOk());
		gateway.reset();
		mockMvc.perform(post("/api/v1/seller/schedules/" + show.scheduleId() + "/cancel")
				.cookie(sessionCookie(new CustomUserDetails(1L, AccountType.SELLER, "STAFF", 1L)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "출연자 사정"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.refundRequestedCount").value(1));
		int refundAmount = 2 * (PRICE + BOOKING_FEE);

		assertThat(bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).isEqualTo(1);
		assertThat(bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).isZero();

		Map<String, Object> refund = scheduleRefund(paid.bookingId());
		assertThat(refund).containsEntry("status", "REQUESTED").containsEntry("amount", refundAmount)
			.containsEntry("reason_detail", "출연자 사정");
		// 환불에 성공하기 전에는 예매·결제·좌석이 그대로다
		assertThat(bookingCancelFacade.applyScheduleCancels(LocalDateTime.now())).isZero();
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("PARTIAL_CANCELED");
		assertThat(paymentStatus(paid.bookingId())).isEqualTo("PARTIAL_CANCELED");
		assertThat(activeSeatCount(paid.bookingId())).isEqualTo(2);
		assertThat(gateway.cancelCalls()).isEmpty();

		refundRetryFacade.sendRequested(LocalDateTime.now());
		assertThat(bookingCancelFacade.applyScheduleCancels(LocalDateTime.now())).isEqualTo(1);
		assertThat(bookingCancelFacade.applyScheduleCancels(LocalDateTime.now())).isZero();

		assertThat(gateway.cancelCalls()).containsExactly(
			new MockPaymentGateway.CancelCall("pay_key_ok", refundAmount, (String) refund.get("idempotency_key")));
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("CANCELED");
		assertThat(paymentStatus(paid.bookingId())).isEqualTo("CANCELED");
		assertThat(activeSeatCount(paid.bookingId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE refund_id = ? AND cancel_fee = 0",
			Long.class, refund.get("id"))).isEqualTo(2);
		show.seatIds().forEach(seatId -> assertThat(seatStatus(seatId)).isEqualTo("AVAILABLE"));
	}

	@Test
	@DisplayName("판매자가 공연을 취소하면 취소한 회차의 예매에 수수료 없는 환불을 만들고, 환불이 끝난 뒤 예매를 취소한다")
	void refundsBookingsOfCanceledPerformance() throws Exception {
		Show show = show(1);
		Paid paid = pay(signup("first"), show, 1, "pay_key_ok");
		mockMvc.perform(post("/api/v1/seller/performances/" + jdbc.queryForObject(
				"SELECT performance_id FROM booking WHERE id = ?", Long.class, paid.bookingId()) + "/cancel")
				.cookie(sessionCookie(new CustomUserDetails(1L, AccountType.SELLER, "STAFF", 1L)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", "제작사 사정"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.refundRequestedCount").value(1));

		assertThat(bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).isEqualTo(1);
		refundRetryFacade.sendRequested(LocalDateTime.now());
		assertThat(bookingCancelFacade.applyScheduleCancels(LocalDateTime.now())).isEqualTo(1);

		assertThat(scheduleRefund(paid.bookingId())).containsEntry("status", "DONE")
			.containsEntry("amount", PRICE + BOOKING_FEE).containsEntry("reason_detail", "제작사 사정");
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("CANCELED");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
	}

	@Test
	@DisplayName("취소 뒤에 늦게 결제 완료가 된 예매도 환불을 만들고, 한 예매의 처리가 실패해도 다음 예매는 처리한다")
	void picksUpLatePaidBookingAndSkipsBrokenOne() throws Exception {
		Show show = show(2);
		Paid broken = pay(signup("broken"), show, 1, "pay_key_ok");
		User late = signup("late");
		MvcResult booked = book(late.session(), show.scheduleId(), List.of(show.seatIds().get(1))).andReturn();
		Long lateId = Long.parseLong(read(booked, "$.data.bookingId"));
		// 앞 번호 예매는 남은 좌석이 없는 어긋난 데이터다
		jdbc.update("UPDATE booking_seat SET active = false WHERE booking_id = ?", broken.bookingId());
		cancelSchedule(show.scheduleId());

		assertThat(bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).isZero();
		// 결제 보정이 늦게 결제를 확정한 상태
		jdbc.update("UPDATE booking SET status = 'PAID' WHERE id = ?", lateId);
		jdbc.update("""
			INSERT INTO payment (booking_id, order_id, payment_key, method, amount, status, approved_at, created_at)
			VALUES (?, 'BK-LATE-01', 'pay_late', '카드', ?, 'DONE', ?, ?)
			""", lateId, PRICE + BOOKING_FEE, now, now);

		assertThat(bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).isEqualTo(1);

		assertThat(scheduleRefund(lateId)).containsEntry("amount", PRICE + BOOKING_FEE);
		assertThat(count("refund")).isEqualTo(1);
		// 환불을 만들지 못한 예매는 바뀐 것이 없다
		assertThat(bookingStatus(broken.bookingId())).isEqualTo("PAID");
	}

	@Test
	@DisplayName("여러 서버가 동시에 처리해도 환불은 예매당 한 건이고 반영도 한 번이며, DB 는 두 번째 회차 취소 환불을 거부한다")
	void processesOnceConcurrently() throws Exception {
		Show show = show(2);
		Paid paid = pay(signup("first"), show, 2, "pay_key_ok");
		cancelSchedule(show.scheduleId());

		List<Integer> requested = runConcurrently(5,
			i -> () -> bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now()));

		assertThat(requested.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
		assertThat(count("refund")).isEqualTo(1);
		assertThatThrownBy(() -> jdbc.update("""
			INSERT INTO refund (payment_id, booking_id, amount, reason_type, idempotency_key, status, retry_count,
			                    requested_at, last_attempted_at)
			SELECT payment_id, booking_id, amount, reason_type, 'another-key', status, 0, requested_at, last_attempted_at
			  FROM refund
			""")).isInstanceOf(DataIntegrityViolationException.class);

		refundRetryFacade.sendRequested(LocalDateTime.now());
		List<Integer> applied = runConcurrently(5,
			i -> () -> bookingCancelFacade.applyScheduleCancels(LocalDateTime.now()));

		assertThat(applied.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("CANCELED");
		assertThat(activeSeatCount(paid.bookingId())).isZero();
	}

	@Test
	@DisplayName("실패한 환불이 있으면 새로 만들지 않고, 완료되기 전에는 예매에 반영하지 않는다")
	void keepsBookingWhileRefundFailed() throws Exception {
		Show show = show(1);
		Paid paid = pay(signup("first"), show, 1, "refund_reject_key");
		cancelSchedule(show.scheduleId());
		bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now());
		refundRetryFacade.sendRequested(LocalDateTime.now());

		assertThat(bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).isZero();
		assertThat(bookingCancelFacade.applyScheduleCancels(LocalDateTime.now())).isZero();

		assertThat(scheduleRefund(paid.bookingId())).containsEntry("status", "FAILED");
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("PAID");
		assertThat(paymentStatus(paid.bookingId())).isEqualTo("DONE");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("SOLD");
	}

	@Test
	@DisplayName("완료된 환불 금액이 남은 좌석의 합과 달라도 남은 좌석을 모두 취소한다")
	void cancelsAllSeatsEvenIfAmountDiffers() throws Exception {
		Show show = show(2);
		Paid paid = pay(signup("first"), show, 2, "pay_key_ok");
		cancelSchedule(show.scheduleId());
		bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now());
		jdbc.update("UPDATE refund SET amount = ?, status = 'DONE'", PRICE);

		assertThat(bookingCancelFacade.applyScheduleCancels(LocalDateTime.now())).isEqualTo(1);

		assertThat(bookingStatus(paid.bookingId())).isEqualTo("CANCELED");
		assertThat(activeSeatCount(paid.bookingId())).isZero();
	}

	@Test
	@DisplayName("예매 락을 다른 작업이 쥐고 있으면 기다리지 않고 건너뛰고 다음 실행에서 처리한다")
	void skipsLockedBooking() throws Exception {
		Show show = show(1);
		Paid paid = pay(signup("first"), show, 1, "pay_key_ok");
		cancelSchedule(show.scheduleId());
		RLock lock = redissonClient.getLock("tickting:lock:booking:" + paid.bookingId());
		lock.lock();
		try {
			// 락은 스레드마다 따로라서 다른 스레드에서 부른다
			assertThat(CompletableFuture.supplyAsync(
				() -> bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).get(10, TimeUnit.SECONDS)).isZero();
		} finally {
			lock.unlock();
		}

		assertThat(bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 회차 취소: 직접 취소와 겹침 (예매 락이 듣지 않을 때)

	/** 직접 취소(BKG-06)의 1단계를 예매 락 없이 한다. beforeApply 는 계산한 뒤 반영하기 전에 끼어든다. */
	private void directCancelWithoutLock(Long userId, Paid paid, List<Long> bookingSeatIds, Runnable beforeApply) {
		LocalDateTime requestedAt = LocalDateTime.now();
		BookingCancelPlan plan = bookingService.planCancel(userId, paid.bookingId(), bookingSeatIds, requestedAt);
		beforeApply.run();
		Payment payment = paymentService.getRefundable(paid.bookingId());
		Refund refund = refundService.start(payment, paid.bookingId(), plan.refundAmount(), RefundReasonType.USER_CANCEL,
			requestedAt);
		paymentService.applyCancel(payment, plan.fullyCanceled());
		bookingService.applyCancel(plan, refund.getId(), requestedAt);
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("먼저 반영 중인 직접 취소가 있으면 환불 만들기는 그 커밋을 기다렸다가 남은 좌석만큼만 환불한다")
	void waitsForOngoingUserCancel() throws Exception {
		Show show = show(3);
		User user = signup("first");
		Paid paid = pay(user, show, 3, "pay_key_ok");
		CountDownLatch applied = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		CompletableFuture<Void> direct = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(tx -> {
			directCancelWithoutLock(user.id(), paid, List.of(paid.bookingSeatIds().get(0)), () -> { });
			applied.countDown();
			await(commit);
		}));
		await(applied);
		cancelSchedule(show.scheduleId());

		CompletableFuture<Integer> request = CompletableFuture.supplyAsync(
			() -> bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now()));
		assertThatThrownBy(() -> request.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
		commit.countDown();
		direct.get(10, TimeUnit.SECONDS);

		assertThat(request.get(10, TimeUnit.SECONDS)).isEqualTo(1);
		assertThat(scheduleRefund(paid.bookingId())).containsEntry("amount", 2 * (PRICE + BOOKING_FEE));
	}

	@ParameterizedTest(name = "좌석 {0}장")
	@ValueSource(ints = {1, 2})
	@DisplayName("회차 취소 뒤에 반영되는 직접 취소(일부·전체)는 환불 행까지 되돌려져 회차 취소 환불만 남는다")
	void rollsBackLateUserCancel(int seatCount) throws Exception {
		Show show = show(2);
		User user = signup("first");
		Paid paid = pay(user, show, 2, "pay_key_ok");
		CountDownLatch planned = new CountDownLatch(1);
		CountDownLatch apply = new CountDownLatch(1);
		CompletableFuture<Void> direct = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(tx ->
			directCancelWithoutLock(user.id(), paid, paid.bookingSeatIds().subList(0, seatCount), () -> {
				planned.countDown();
				await(apply);
			})));
		await(planned);
		cancelSchedule(show.scheduleId());
		assertThat(bookingCancelFacade.requestScheduleRefunds(LocalDateTime.now())).isEqualTo(1);
		apply.countDown();

		assertThatThrownBy(() -> direct.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);

		assertThat(count("refund")).isEqualTo(1);
		assertThat(scheduleRefund(paid.bookingId())).containsEntry("amount", 2 * (PRICE + BOOKING_FEE));
		assertThat(bookingStatus(paid.bookingId())).isEqualTo("PAID");
		assertThat(paymentStatus(paid.bookingId())).isEqualTo("DONE");
		assertThat(activeSeatCount(paid.bookingId())).isEqualTo(2);
	}
}
