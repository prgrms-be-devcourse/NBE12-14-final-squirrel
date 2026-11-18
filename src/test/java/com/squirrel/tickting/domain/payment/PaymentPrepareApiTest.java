package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 결제 준비(BKG-02). 예매는 실제 좌석 점유 API 로 만들고, 실제 PostgreSQL·Redis(Testcontainers)에서 확인한다. */
class PaymentPrepareApiTest extends BookingTestSupport {

	@BeforeEach
	void resetPayments() {
		jdbc.execute("TRUNCATE TABLE payment RESTART IDENTITY");
	}

	@AfterEach
	void cleanUpPayments() {
		jdbc.execute("TRUNCATE TABLE payment RESTART IDENTITY");
	}

	private record BookingRef(Long id, String bookingNo) {
	}

	/** 좌석을 점유해서 결제 대기 예매를 만든다. */
	private BookingRef createBooking(User user, Show show, int seatCount) throws Exception {
		return createBooking(user, show, 0, seatCount);
	}

	/** show 의 fromIndex 번째 좌석부터 seatCount 개를 점유한다. 한 회차에서 여러 예매를 만들 때 좌석이 겹치지 않게 쓴다. */
	private BookingRef createBooking(User user, Show show, int fromIndex, int seatCount) throws Exception {
		MvcResult result = book(user.bearer(), show.scheduleId(), show.seatIds().subList(fromIndex, fromIndex + seatCount))
			.andExpect(status().isCreated())
			.andReturn();
		return new BookingRef(Long.parseLong(read(result, "$.data.bookingId")), read(result, "$.data.bookingNo"));
	}

	private Map<String, Object> onsite() {
		return Map.of("receiveType", "ONSITE");
	}

	private ResultActions prepare(String bearer, Long bookingId, Object body) throws Exception {
		return mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/payments")
			.header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private String paymentCodeOf(MvcResult result) throws Exception {
		return read(result, "$.code");
	}

	// ---------------------------------------------------------------- 정상 흐름

	@Test
	@DisplayName("결제를 준비하면 READY 결제가 만들어지고 토스 결제창에 쓸 주문 ID·주문명·금액·만료 시각이 온다")
	void preparesPayment() throws Exception {
		Show show = show(3);
		User user = signup("first");
		BookingRef booking = createBooking(user, show, 2);

		prepare(user.bearer(), booking.id(), onsite())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.orderId").value("BK" + booking.bookingNo() + "-01"))
			.andExpect(jsonPath("$.data.orderName").value("가상 콘서트 2매"))
			.andExpect(jsonPath("$.data.amount").value(312000))
			.andExpect(jsonPath("$.data.status").value("READY"))
			.andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

		Map<String, Object> payment = jdbc.queryForMap("SELECT * FROM payment");
		assertThat(payment.get("booking_id")).isEqualTo(booking.id());
		assertThat(payment.get("order_id")).isEqualTo("BK" + booking.bookingNo() + "-01");
		assertThat(payment.get("amount")).isEqualTo(312000);
		assertThat(payment.get("status")).isEqualTo("READY");
		assertThat(payment.get("payment_key")).isNull();
		// 주문 ID 는 토스 규칙(6~64자)에 맞아야 한다
		assertThat(((String) payment.get("order_id")).length()).isBetween(6, 64);
	}

	@Test
	@DisplayName("준비를 다시 요청하면 새로 만들지 않고 같은 결제와 주문 ID 를 돌려준다")
	void repeatedPrepareReturnsSamePayment() throws Exception {
		Show show = show(1);
		User user = signup("first");
		BookingRef booking = createBooking(user, show, 1);

		MvcResult first = prepare(user.bearer(), booking.id(), onsite()).andExpect(status().isOk()).andReturn();
		MvcResult second = prepare(user.bearer(), booking.id(), onsite()).andExpect(status().isOk()).andReturn();

		assertThat(read(second, "$.data.paymentId")).isEqualTo(read(first, "$.data.paymentId"));
		assertThat(read(second, "$.data.orderId")).isEqualTo(read(first, "$.data.orderId"));
		assertThat(count("payment")).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 수령 방법

	// ---------------------------------------------------------------- 예매 상태·소유

	@Test
	@DisplayName("없는 예매와 남의 예매는 같은 404 BOOKING006 이고 남의 예매에는 결제가 만들어지지 않는다")
	void notMyBookingIsNotFound() throws Exception {
		Show show = show(1);
		User owner = signup("owner");
		User other = signup("other");
		BookingRef booking = createBooking(owner, show, 1);

		prepare(other.bearer(), booking.id(), onsite())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING006"));
		prepare(other.bearer(), 99_999L, onsite())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING006"));

		assertThat(count("payment")).isZero();
	}

	// ---------------------------------------------------------------- 권한

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("같은 예매에 준비 요청이 동시에 와도 결제는 한 건만 만들어지고 모두 같은 주문 ID 를 받는다")
	void concurrentPrepareCreatesOnePayment() throws Exception {
		Show show = show(2);
		User user = signup("first");
		User warm = signup("warm");
		BookingRef booking = createBooking(user, show, 1);
		BookingRef warmBooking = createBooking(warm, show, 1, 1);
		// 첫 요청은 클래스 로딩 때문에 느려서 락 대기 시간(1초)을 넘기지 않도록 다른 예매로 한 번 미리 실행해 둔다
		prepare(warm.bearer(), warmBooking.id(), onsite()).andExpect(status().isOk());

		List<String> results = runConcurrently(10, i -> () -> {
			MvcResult result = prepare(user.bearer(), booking.id(), onsite()).andReturn();
			return result.getResponse().getStatus() == 200 ? read(result, "$.data.orderId") : paymentCodeOf(result);
		});

		assertThat(results).allMatch(r -> r.equals("BK" + booking.bookingNo() + "-01") || r.equals("COMMON004"));
		assertThat(results.stream().filter(r -> r.startsWith("BK")).count()).isGreaterThan(0);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE booking_id = ?", Long.class, booking.id()))
			.isEqualTo(1);
	}
}
