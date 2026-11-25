package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 좌석 점유·예매 생성(BKG-01). 실제 PostgreSQL·Redis(Testcontainers)에서 API 를 호출한다.
 * 동시성 테스트는 스레드마다 트랜잭션이 따로 있어야 해서 테스트 트랜잭션으로 감싸지 않고, 만든 데이터를 직접 지운다.
 * 시각은 자바에서 만든 값을 넣는다. (컨테이너 DB 와 애플리케이션의 시간대가 달라도 어긋나지 않게)
 */
class BookingCreateApiTest extends BookingTestSupport {

	/** DistributedLockExecutor 가 락 이름 앞에 붙이는 접두사. 다른 곳에서 같은 락을 잡아 보려고 같은 이름을 쓴다. */
	private static final String LOCK_PREFIX = "tickting:lock:";

	@Autowired
	RedissonClient redissonClient;

	@Autowired
	DistributedLockExecutor lockExecutor;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	BookingService bookingService;

	@Autowired
	ScheduleSeatService scheduleSeatService;

	// ---------------------------------------------------------------- 정상 흐름

	@Test
	@DisplayName("좌석 두 개를 점유하면 201 과 함께 결제 대기 예매가 만들어지고, 좌석·금액·주문자가 복사된다")
	void createsPendingBooking() throws Exception {
		Show show = show(3);
		User user = signup("first");

		MvcResult result = book(user.bearer(), show.scheduleId(), show.seatIds().subList(0, 2))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("PENDING"))
			.andExpect(jsonPath("$.data.seats.length()").value(2))
			.andExpect(jsonPath("$.data.seats[0].zoneName").value("F2구역"))
			.andExpect(jsonPath("$.data.seats[0].rowLabel").value("9열"))
			.andExpect(jsonPath("$.data.seats[0].seatNumber").value("1"))
			.andExpect(jsonPath("$.data.seats[1].seatNumber").value("2"))
			.andExpect(jsonPath("$.data.seats[0].gradeName").value("R"))
			.andExpect(jsonPath("$.data.seats[0].price").value(154000))
			.andExpect(jsonPath("$.data.seats[0].bookingFee").value(2000))
			.andExpect(jsonPath("$.data.ticketAmount").value(308000))
			.andExpect(jsonPath("$.data.bookingFeeAmount").value(4000))
			.andExpect(jsonPath("$.data.totalAmount").value(312000))
			.andReturn();

		assertThat(read(result, "$.data.bookingNo")).matches("\\d{8}[A-Z0-9]{7}");
		Map<String, Object> booking = jdbc.queryForMap("SELECT * FROM booking");
		assertThat(booking.get("user_id")).isEqualTo(user.id());
		assertThat(booking.get("schedule_id")).isEqualTo(show.scheduleId());
		assertThat(booking.get("performance_id")).isEqualTo(show.performanceId());
		assertThat(booking.get("business_id")).isEqualTo(jdbc.queryForObject(
			"SELECT business_id FROM performance WHERE id = ?", Long.class, show.performanceId()));
		assertThat(booking.get("status")).isEqualTo("PENDING");
		assertThat(booking.get("receive_type")).isEqualTo("ONSITE");
		assertThat(booking.get("orderer_name")).isEqualTo("홍길동");
		assertThat(booking.get("orderer_phone")).isEqualTo("01012345678");
		assertThat(booking.get("orderer_email")).isEqualTo("first@example.com");
		LocalDateTime expiresAt = jdbc.queryForObject("SELECT expires_at FROM booking", LocalDateTime.class);
		assertThat(expiresAt).isBetween(LocalDateTime.now().plusMinutes(6), LocalDateTime.now().plusMinutes(8));

		assertThat(count("booking_seat")).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE active", Long.class)).isEqualTo(2);
		for (Long seatId : show.seatIds().subList(0, 2)) {
			assertThat(seatStatus(seatId)).isEqualTo("HELD");
			assertThat(jdbc.queryForObject("SELECT held_by FROM schedule_seat WHERE id = ?", Long.class, seatId))
				.isEqualTo(user.id());
		}
		assertThat(seatStatus(show.seatIds().get(2))).isEqualTo("AVAILABLE");
	}

	// ---------------------------------------------------------------- 입력·권한

	// ---------------------------------------------------------------- 판매 조건

	// ---------------------------------------------------------------- 점유 충돌

	@Test
	@DisplayName("이미 점유된 좌석이 섞여 있으면 409 BOOKING000 이고, 나머지 좌석도 점유되지 않으며 예매도 없다")
	void rejectsAllWhenOneSeatIsTaken() throws Exception {
		Show show = show(2);
		User first = signup("first");
		User second = signup("second");
		book(first.bearer(), show.scheduleId(), List.of(show.seatIds().get(0))).andExpect(status().isCreated());

		book(second.bearer(), show.scheduleId(), show.seatIds())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING000"));

		assertThat(seatStatus(show.seatIds().get(1))).isEqualTo("AVAILABLE");
		assertThat(count("booking")).isEqualTo(1);
		assertThat(count("booking_seat")).isEqualTo(1);
	}

	@Test
	@DisplayName("락과 상태 조건이 모두 뚫려 같은 좌석의 활성 예매가 이미 있어도 UNIQUE 인덱스가 막아 409 BOOKING000 이고 점유는 되돌려진다")
	void uniqueIndexIsLastDefense() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Long seat = show.seatIds().get(0);
		// 좌석은 AVAILABLE 인데 활성 예매 좌석이 남아 있는, 락이 뚫렸을 때나 생길 법한 어긋난 상태
		jdbc.update("""
			INSERT INTO booking_seat (booking_id, schedule_seat_id, zone_name, row_label, seat_number, grade_name,
			                          price, booking_fee, active)
			VALUES (999, ?, 'F2구역', '9열', '1', 'R', 154000, 2000, true)
			""", seat);

		book(user.bearer(), show.scheduleId(), List.of(seat))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING000"));

		assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
		assertThat(count("booking")).isZero();
	}

	// ---------------------------------------------------------------- 1인 최대 매수

	@Test
	@DisplayName("한 번에 한도보다 많이 요청하면 409 BOOKING001 이고 아무것도 점유되지 않는다")
	void rejectsRequestOverLimit() throws Exception {
		Show show = show(MAX_PER_PERSON + 1);
		User user = signup("first");

		book(user.bearer(), show.scheduleId(), show.seatIds())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING001"));

		assertThat(count("booking")).isZero();
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
	}

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("20명이 같은 좌석을 동시에 요청해도 한 명만 성공하고, 좌석과 예매는 한 개씩만 남는다")
	void onlyOneWinsOnSameSeat() throws Exception {
		Show show = show(2);
		Long contested = show.seatIds().get(0);
		List<User> users = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			users.add(signup("user" + i));
		}
		// 첫 요청은 클래스 로딩 때문에 느려서, 락 대기 시간(1초)을 넘기지 않도록 다른 좌석으로 한 번 미리 실행해 둔다
		User warm = signup("warm");
		assertThat(bookForResult(warm.bearer(), show.scheduleId(), List.of(show.seatIds().get(1)))).isEqualTo("OK");

		List<String> results = runConcurrently(20,
			i -> () -> bookForResult(users.get(i).bearer(), show.scheduleId(), List.of(contested)));

		assertThat(countOf("OK", results)).isEqualTo(1);
		// 진 요청은 좌석이 이미 잡혔다는 409 이거나, 줄이 길어 락 대기 시간을 넘긴 409 다
		assertThat(results.stream().filter(code -> !code.equals("OK")))
			.allMatch(code -> code.equals("BOOKING000") || code.equals("COMMON004"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE schedule_seat_id = ?", Long.class, contested))
			.isEqualTo(1);
		assertThat(seatStatus(contested)).isEqualTo("HELD");
		Long holder = jdbc.queryForObject("SELECT held_by FROM schedule_seat WHERE id = ?", Long.class, contested);
		assertThat(jdbc.queryForObject("""
			SELECT b.user_id FROM booking b JOIN booking_seat bs ON bs.booking_id = b.id WHERE bs.schedule_seat_id = ?
			""", Long.class, contested)).isEqualTo(holder);
	}

	@Test
	@DisplayName("같은 사용자가 서로 다른 좌석을 동시에 요청해도 1인 최대 매수를 넘지 않는다")
	void sameUserCannotExceedLimitConcurrently() throws Exception {
		int requests = 6;
		Show show = show(requests + 1);
		User user = signup("first");
		User warm = signup("warm");
		assertThat(bookForResult(warm.bearer(), show.scheduleId(), List.of(show.seatIds().get(requests)))).isEqualTo("OK");

		List<String> results = runConcurrently(requests,
			i -> () -> bookForResult(user.bearer(), show.scheduleId(), List.of(show.seatIds().get(i))));

		assertThat(countOf("OK", results)).isEqualTo(MAX_PER_PERSON);
		assertThat(countOf("BOOKING001", results)).isEqualTo(requests - MAX_PER_PERSON);
		assertThat(jdbc.queryForObject("""
			SELECT count(*) FROM booking_seat bs JOIN booking b ON b.id = bs.booking_id
			WHERE b.user_id = ? AND bs.active
			""", Long.class, user.id())).isEqualTo(MAX_PER_PERSON);
	}

	@Test
	@DisplayName("다른 요청이 좌석 락을 쥐고 있으면 1초쯤 기다린 뒤 409 COMMON004 로 실패하고 아무것도 바뀌지 않는다")
	void failsFastWhenLockIsBusy() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Long seat = show.seatIds().get(0);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		// 락은 잡은 스레드의 것이라(같은 스레드는 다시 잡힌다) 다른 스레드에서 잡고 있는다
		Thread holder = new Thread(() -> {
			RLock lock = redissonClient.getLock(LOCK_PREFIX + "seat:" + seat);
			lock.lock();
			locked.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				lock.unlock();
			}
		});
		holder.start();
		try {
			locked.await();
			long startedAt = System.nanoTime();

			book(user.bearer(), show.scheduleId(), List.of(seat))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMON004"));

			Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);
			assertThat(waited).isBetween(Duration.ofMillis(900), Duration.ofSeconds(5));
		} finally {
			release.countDown();
			holder.join();
		}

		assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
		assertThat(count("booking")).isZero();
	}
}
