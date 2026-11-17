package com.squirrel.tickting.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import java.time.LocalDateTime;
import java.util.HashMap;
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

	private Map<String, Object> delivery() {
		Map<String, Object> body = new HashMap<>();
		body.put("receiveType", "DELIVERY");
		body.put("recipientName", "김수령");
		body.put("recipientPhone", "01022223333");
		body.put("recipientAddress", "서울시 마포구 월드컵북로 1길 1");
		return body;
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
	@DisplayName("결제 준비는 좌석 점유 시간을 늘리지 않는다 (연장은 승인을 시작할 때 한 번만 한다)")
	void doesNotExtendHold() throws Exception {
		Show show = show(1);
		User user = signup("first");
		BookingRef booking = createBooking(user, show, 1);
		LocalDateTime before = jdbc.queryForObject("SELECT expires_at FROM booking WHERE id = ?", LocalDateTime.class, booking.id());
		LocalDateTime heldBefore = jdbc.queryForObject("SELECT held_until FROM schedule_seat WHERE id = ?", LocalDateTime.class,
			show.seatIds().get(0));

		prepare(user.bearer(), booking.id(), onsite()).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT expires_at FROM booking WHERE id = ?", LocalDateTime.class, booking.id()))
			.isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT held_until FROM schedule_seat WHERE id = ?", LocalDateTime.class,
			show.seatIds().get(0))).isEqualTo(heldBefore);
	}

	@Test
	@DisplayName("배송을 고르면 받는 사람 정보가 예매에 저장되고, 다시 현장 수령으로 준비하면 지워진다")
	void savesAndClearsDeliveryInfo() throws Exception {
		Show show = show(1);
		User user = signup("first");
		BookingRef booking = createBooking(user, show, 1);

		prepare(user.bearer(), booking.id(), delivery()).andExpect(status().isOk());
		Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM booking WHERE id = ?", booking.id());
		assertThat(saved.get("receive_type")).isEqualTo("DELIVERY");
		assertThat(saved.get("recipient_name")).isEqualTo("김수령");
		assertThat(saved.get("recipient_phone")).isEqualTo("01022223333");
		assertThat(saved.get("recipient_address")).isEqualTo("서울시 마포구 월드컵북로 1길 1");

		prepare(user.bearer(), booking.id(), onsite()).andExpect(status().isOk());
		Map<String, Object> cleared = jdbc.queryForMap("SELECT * FROM booking WHERE id = ?", booking.id());
		assertThat(cleared.get("receive_type")).isEqualTo("ONSITE");
		assertThat(cleared.get("recipient_name")).isNull();
		assertThat(cleared.get("recipient_phone")).isNull();
		assertThat(cleared.get("recipient_address")).isNull();
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

	@Test
	@DisplayName("이전 결제 시도가 실패했으면 일련번호를 올려 새 주문 ID 로 준비한다")
	void newOrderIdAfterFailedAttempt() throws Exception {
		Show show = show(1);
		User user = signup("first");
		BookingRef booking = createBooking(user, show, 1);
		prepare(user.bearer(), booking.id(), onsite()).andExpect(status().isOk());
		jdbc.update("UPDATE payment SET status = 'FAILED', fail_reason = '카드 한도 초과'");

		prepare(user.bearer(), booking.id(), onsite())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.orderId").value("BK" + booking.bookingNo() + "-02"))
			.andExpect(jsonPath("$.data.status").value("READY"));

		assertThat(count("payment")).isEqualTo(2);
	}

	// ---------------------------------------------------------------- 수령 방법

	@Test
	@DisplayName("배송을 골랐는데 받는 사람 이름·연락처·주소 중 하나라도 비었으면 400 PAYMENT001 이다")
	void deliveryNeedsRecipient() throws Exception {
		Show show = show(1);
		User user = signup("first");
		BookingRef booking = createBooking(user, show, 1);

		for (String missing : List.of("recipientName", "recipientPhone", "recipientAddress")) {
			Map<String, Object> body = delivery();
			body.put(missing, " ");
			prepare(user.bearer(), booking.id(), body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PAYMENT001"));
		}
		prepare(user.bearer(), booking.id(), Map.of("receiveType", "DELIVERY"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT001"));

		assertThat(count("payment")).isZero();
	}

	@Test
	@DisplayName("공연이 받지 않는 수령 방법이면 400 PAYMENT000 이다 (현장 전용 공연의 배송, 배송 전용 공연의 현장)")
	void rejectsNotAllowedReceiveType() throws Exception {
		User user = signup("first");
		Show onsiteOnly = show(1, "PUBLISHED", "ON_SALE", now.plusDays(10), now.minusDays(1), now.plusDays(30), "ONSITE", 2000);
		Show deliveryOnly = show(1, "PUBLISHED", "ON_SALE", now.plusDays(10), now.minusDays(1), now.plusDays(30), "DELIVERY", 2000);
		BookingRef first = createBooking(user, onsiteOnly, 1);
		BookingRef second = createBooking(user, deliveryOnly, 1);

		prepare(user.bearer(), first.id(), delivery())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT000"));
		prepare(user.bearer(), second.id(), onsite())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PAYMENT000"));
		// 허용하는 쪽은 통과한다
		prepare(user.bearer(), first.id(), onsite()).andExpect(status().isOk());
		prepare(user.bearer(), second.id(), delivery()).andExpect(status().isOk());
	}

	@Test
	@DisplayName("수령 방법이 비었거나 알 수 없는 값이면 400 COMMON000 이다")
	void rejectsInvalidBody() throws Exception {
		Show show = show(1);
		User user = signup("first");
		BookingRef booking = createBooking(user, show, 1);

		prepare(user.bearer(), booking.id(), Map.of())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
		prepare(user.bearer(), booking.id(), Map.of("receiveType", "PIGEON"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}

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

	@Test
	@DisplayName("결제 대기가 아니거나 결제 제한 시각이 지났거나 공연이 공개 상태가 아니면 409 BOOKING007 이다")
	void notPayableBooking() throws Exception {
		User user = signup("first");
		Show expiredShow = show(1);
		Show paidShow = show(1);
		Show canceledShow = show(1);
		Show expiredStatusShow = show(1);
		BookingRef expired = createBooking(user, expiredShow, 1);
		BookingRef paid = createBooking(user, paidShow, 1);
		BookingRef canceledPerformance = createBooking(user, canceledShow, 1);
		BookingRef expiredStatus = createBooking(user, expiredStatusShow, 1);

		// 스케줄러가 아직 정리하지 않았어도 시각이 지난 예매는 막는다
		jdbc.update("UPDATE booking SET expires_at = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), expired.id());
		jdbc.update("UPDATE booking SET status = 'PAID' WHERE id = ?", paid.id());
		jdbc.update("UPDATE performance SET status = 'CANCELED' WHERE id = ?", canceledShow.performanceId());
		jdbc.update("UPDATE booking SET status = 'EXPIRED' WHERE id = ?", expiredStatus.id());

		for (BookingRef booking : List.of(expired, paid, canceledPerformance, expiredStatus)) {
			prepare(user.bearer(), booking.id(), onsite())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BOOKING007"));
		}
		assertThat(count("payment")).isZero();
	}

	// ---------------------------------------------------------------- 권한

	@Test
	@DisplayName("토큰 없이 호출하면 401, 판매자·관리자 토큰으로 호출하면 403 이다")
	void needsUserRole() throws Exception {
		Show show = show(1);
		User user = signup("first");
		BookingRef booking = createBooking(user, show, 1);
		String seller = "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.SELLER, "OWNER", 1L));
		String admin = "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.ADMIN, null, null));

		mockMvc.perform(post("/api/v1/bookings/" + booking.id() + "/payments")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(onsite())))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
		prepare(seller, booking.id(), onsite())
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
		prepare(admin, booking.id(), onsite())
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));

		assertThat(count("payment")).isZero();
	}

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
