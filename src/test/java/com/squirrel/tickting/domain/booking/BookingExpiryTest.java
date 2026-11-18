package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.facade.BookingExpiryFacade;
import com.squirrel.tickting.domain.booking.facade.BookingProperties;
import com.squirrel.tickting.domain.booking.scheduler.BookingExpiryScheduler;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DurationFormat;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.format.datetime.standard.DurationFormatterUtils;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 결제하지 않고 떠난 좌석 정리. 주기 작업은 테스트에서 꺼 두고(app.booking.expiry-enabled=false), 작업이 부르는 파사드를 직접 부른다.
 * 실제 PostgreSQL(Testcontainers)에서 SKIP LOCKED 까지 확인한다.
 */
class BookingExpiryTest extends BookingTestSupport {

	@Autowired
	BookingExpiryFacade bookingExpiryFacade;

	@Autowired
	BookingProperties properties;

	@Autowired
	Environment environment;

	private int bookingSequence;

	/**
	 * 예매 한 건과 예매 좌석을 직접 넣고, 좌석을 그 예매의 점유 상태로 맞춘다. 결제 대기면 좌석은 HELD, 결제 완료면 SOLD 다.
	 *
	 * @return 예매 번호(booking.id)
	 */
	private Long insertBooking(Show show, int seatIndex, Long userId, LocalDateTime expiresAt, String bookingStatus) {
		Long seatId = show.seatIds().get(seatIndex);
		Long bookingId = jdbc.queryForObject("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, status, receive_type, orderer_name,
			                     orderer_phone, orderer_email, ticket_amount, booking_fee_amount, total_amount,
			                     cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, 'ONSITE', '홍길동', '01012345678', 'a@example.com', 154000, 2000, 156000,
			        '[]'::jsonb, ?, ?, ?)
			RETURNING id
			""", Long.class, "TEST" + (++bookingSequence), userId, show.scheduleId(), show.performanceId(), bookingStatus,
			expiresAt, now, now);
		jdbc.update("""
			INSERT INTO booking_seat (booking_id, schedule_seat_id, zone_name, row_label, seat_number, grade_name,
			                          price, booking_fee, active)
			VALUES (?, ?, 'F2구역', '9열', '1', 'R', 154000, 2000, true)
			""", bookingId, seatId);
		jdbc.update("UPDATE schedule_seat SET status = ?, held_by = ?, held_until = ? WHERE id = ?",
			bookingStatus.equals("PAID") ? "SOLD" : "HELD", userId, expiresAt, seatId);
		return bookingId;
	}

	private String bookingStatus(Long bookingId) {
		return jdbc.queryForObject("SELECT status FROM booking WHERE id = ?", String.class, bookingId);
	}

	private boolean seatActive(Long bookingId) {
		return jdbc.queryForObject("SELECT active FROM booking_seat WHERE booking_id = ?", Boolean.class, bookingId);
	}

	private Long heldBy(Long seatId) {
		return jdbc.queryForObject("SELECT held_by FROM schedule_seat WHERE id = ?", Long.class, seatId);
	}

	private LocalDateTime heldUntil(Long seatId) {
		return jdbc.queryForObject("SELECT held_until FROM schedule_seat WHERE id = ?", LocalDateTime.class, seatId);
	}

	@Test
	@DisplayName("결제 제한 시각이 지난 예매는 EXPIRED 가 되고, 예매 좌석은 비활성, 판매 좌석은 AVAILABLE 로 돌아가 점유 정보가 지워진다")
	void expiresOverdueBooking() {
		Show show = show(1);
		Long bookingId = insertBooking(show, 0, 7L, LocalDateTime.now().minusMinutes(1), "PENDING");

		int released = bookingExpiryFacade.expireOverdue(LocalDateTime.now(), 100);

		assertThat(released).isEqualTo(1);
		assertThat(bookingStatus(bookingId)).isEqualTo("EXPIRED");
		assertThat(seatActive(bookingId)).isFalse();
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
		assertThat(heldBy(show.seatIds().get(0))).isNull();
		assertThat(heldUntil(show.seatIds().get(0))).isNull();
	}

	@Test
	@DisplayName("아직 결제 제한 시각이 지나지 않은 예매와 좌석은 건드리지 않는다")
	void keepsBookingWithinTimeLimit() {
		Show show = show(1);
		Long bookingId = insertBooking(show, 0, 7L, LocalDateTime.now().plusMinutes(3), "PENDING");

		int released = bookingExpiryFacade.expireOverdue(LocalDateTime.now(), 100);

		assertThat(released).isZero();
		assertThat(bookingStatus(bookingId)).isEqualTo("PENDING");
		assertThat(seatActive(bookingId)).isTrue();
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("HELD");
		assertThat(heldBy(show.seatIds().get(0))).isEqualTo(7L);
	}

	@Test
	@DisplayName("결제가 끝난 예매는 시각이 지나 있어도 만료되지 않고 좌석도 SOLD 로 남는다")
	void keepsPaidBooking() {
		Show show = show(1);
		Long bookingId = insertBooking(show, 0, 7L, LocalDateTime.now().minusMinutes(30), "PAID");

		int released = bookingExpiryFacade.expireOverdue(LocalDateTime.now(), 100);

		assertThat(released).isZero();
		assertThat(bookingStatus(bookingId)).isEqualTo("PAID");
		assertThat(seatActive(bookingId)).isTrue();
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("SOLD");
	}

	@Test
	@DisplayName("정리 전에는 만료된 좌석을 점유할 수 없고, 정리하면 다른 사람이 점유할 수 있으며 원래 주인의 1인 최대 매수도 비워진다")
	void freedSeatsCanBeBookedAgain() throws Exception {
		Show show = show(MAX_PER_PERSON + 1);
		User owner = signup("owner");
		User other = signup("other");
		for (int i = 0; i < MAX_PER_PERSON; i++) {
			insertBooking(show, i, owner.id(), LocalDateTime.now().minusMinutes(1), "PENDING");
		}
		Long contested = show.seatIds().get(0);

		// 시각은 지났지만 아직 정리 전이라 점유할 수 없고, 주인의 한도도 차 있다
		book(other.bearer(), show.scheduleId(), List.of(contested))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING000"));
		book(owner.bearer(), show.scheduleId(), List.of(show.seatIds().get(MAX_PER_PERSON)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING001"));

		assertThat(bookingExpiryFacade.expireOverdue(LocalDateTime.now(), 100)).isEqualTo(MAX_PER_PERSON);

		book(other.bearer(), show.scheduleId(), List.of(contested)).andExpect(status().isCreated());
		book(owner.bearer(), show.scheduleId(), List.of(show.seatIds().get(MAX_PER_PERSON))).andExpect(status().isCreated());
		assertThat(heldBy(contested)).isEqualTo(other.id());
	}

	@Test
	@DisplayName("만료된 예매가 배치 크기보다 많으면 나눠서 모두 처리한다")
	void processesInBatches() {
		Show show = show(10);
		Show second = show(10);
		Show third = show(5);
		List<Long> bookingIds = new ArrayList<>();
		for (Show target : List.of(show, second, third)) {
			for (int i = 0; i < target.seatIds().size(); i++) {
				bookingIds.add(insertBooking(target, i, 7L, LocalDateTime.now().minusMinutes(1), "PENDING"));
			}
		}

		int released = bookingExpiryFacade.expireOverdue(LocalDateTime.now(), 10);

		assertThat(released).isEqualTo(25);
		assertThat(bookingIds).allSatisfy(id -> assertThat(bookingStatus(id)).isEqualTo("EXPIRED"));
	}

	@Test
	@DisplayName("주기 작업은 설정값(app.booking.expiry-interval)의 1분 주기로 등록되고, 실행하면 만료된 예매를 정리한다")
	void schedulerRunsWithConfiguredInterval() throws Exception {
		Scheduled scheduled = BookingExpiryScheduler.class.getMethod("expireOverdue").getAnnotation(Scheduled.class);
		String interval = environment.resolvePlaceholders(scheduled.fixedDelayString());
		assertThat(DurationFormatterUtils.detectAndParse(interval, DurationFormat.Unit.MILLIS)).isEqualTo(Duration.ofMinutes(1));
		assertThat(properties.expiryBatchSize()).isEqualTo(500);

		Show show = show(1);
		Long bookingId = insertBooking(show, 0, 7L, LocalDateTime.now().minusMinutes(1), "PENDING");

		new BookingExpiryScheduler(bookingExpiryFacade, properties).expireOverdue();

		assertThat(bookingStatus(bookingId)).isEqualTo("EXPIRED");
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
	}

	@Test
	@DisplayName("서버 여러 대가 동시에 정리해도 같은 예매를 두 번 처리하지 않고 모두 한 번씩 처리한다")
	void concurrentCleanupProcessesEachBookingOnce() throws Exception {
		int threads = 8;
		List<Long> bookingIds = new ArrayList<>();
		List<Long> seatIds = new ArrayList<>();
		for (int s = 0; s < 5; s++) {
			Show show = show(10);
			for (int i = 0; i < 10; i++) {
				bookingIds.add(insertBooking(show, i, 7L, LocalDateTime.now().minusMinutes(1), "PENDING"));
				seatIds.add(show.seatIds().get(i));
			}
		}

		// 배치를 작게 해서 서버들이 서로 다른 예매 묶음을 가져가며 겹치게 한다
		List<Integer> results = runConcurrently(threads, i -> () -> bookingExpiryFacade.expireOverdue(LocalDateTime.now(), 7));

		assertThat(results.stream().mapToInt(Integer::intValue).sum()).isEqualTo(50);
		assertThat(bookingIds).allSatisfy(id -> {
			assertThat(bookingStatus(id)).isEqualTo("EXPIRED");
			assertThat(seatActive(id)).isFalse();
		});
		assertThat(seatIds).allSatisfy(id -> assertThat(seatStatus(id)).isEqualTo("AVAILABLE"));
	}
}
