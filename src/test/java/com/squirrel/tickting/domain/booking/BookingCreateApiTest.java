package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.service.BookingCreateCommand;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 좌석 점유·예매 생성(BKG-01). 실제 PostgreSQL·Redis(Testcontainers)에서 API 를 호출한다.
 * 동시성 테스트는 스레드마다 트랜잭션이 따로 있어야 해서 테스트 트랜잭션으로 감싸지 않고, 만든 데이터를 직접 지운다.
 * 시각은 자바에서 만든 값을 넣는다. (컨테이너 DB 와 애플리케이션의 시간대가 달라도 어긋나지 않게)
 */
class BookingCreateApiTest extends IntegrationTestSupport {

	private static final String SIGNUP = "/api/v1/auth/users/signup";
	/** DistributedLockExecutor 가 락 이름 앞에 붙이는 접두사. 다른 곳에서 같은 락을 잡아 보려고 같은 이름을 쓴다. */
	private static final String LOCK_PREFIX = "tickting:lock:";
	private static final int MAX_PER_PERSON = 4;

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

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private Long venueId;
	private Long zoneId;
	private Long cancelPolicyId;
	private List<Long> venueSeatIds;
	private final List<Long> performanceIds = new ArrayList<>();

	@BeforeEach
	void setUpData() {
		jdbc.execute("TRUNCATE TABLE booking, booking_seat, schedule_seat RESTART IDENTITY");
		cancelPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('가상 아레나', '서울 가상구 1', '서울', 100, 'ACTIVE', ?, ?) RETURNING id
			""", Long.class, now, now);
		zoneId = jdbc.queryForObject("""
			INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, 'F2구역', 1) RETURNING id
			""", Long.class, venueId);
		venueSeatIds = new ArrayList<>();
		for (int number = 1; number <= 12; number++) {
			venueSeatIds.add(jdbc.queryForObject("""
				INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order)
				VALUES (?, ?, '9열', ?, ?) RETURNING id
				""", Long.class, venueId, zoneId, String.valueOf(number), number));
		}
	}

	@AfterEach
	void cleanUpData() {
		for (Long performanceId : performanceIds) {
			jdbc.update("DELETE FROM performance_schedule WHERE performance_id = ?", performanceId);
			jdbc.update("DELETE FROM seat_grade WHERE performance_id = ?", performanceId);
			jdbc.update("DELETE FROM performance WHERE id = ?", performanceId);
		}
		jdbc.update("DELETE FROM venue_seat WHERE venue_id = ?", venueId);
		jdbc.update("DELETE FROM venue_zone WHERE venue_id = ?", venueId);
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
		jdbc.execute("TRUNCATE TABLE booking, booking_seat, schedule_seat RESTART IDENTITY");
	}

	// ---------------------------------------------------------------- 데이터 만들기

	/** 공연·회차·등급(R석 154,000원)과 판매 좌석. seatIds 는 판매 좌석(schedule_seat) 번호다. */
	private record Show(Long performanceId, Long scheduleId, List<Long> seatIds) {
	}

	private Show show(int seatCount) {
		return show(seatCount, "PUBLISHED", "ON_SALE", now.plusDays(10), now.minusDays(1), now.plusDays(30), "BOTH", 2000);
	}

	private Show show(int seatCount, String performanceStatus, String scheduleStatus, LocalDateTime startAt,
		LocalDateTime saleOpenAt, LocalDateTime saleCloseAt, String receiveType, Integer bookingFee) {
		Long performanceId = jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, cast_info, poster_url, notice, guide, status, sale_open_at,
			                         sale_close_at, max_per_person, receive_type, booking_fee, created_at, updated_at)
			VALUES (1, ?, 1, ?, '가상 콘서트', 'CONCERT', 12, 150, '가상 밴드', 'https://cdn.example.com/p.png',
			        '입장 안내', '촬영 금지', ?, ?, ?, ?, ?, ?, ?, ?)
			RETURNING id
			""", Long.class, venueId, cancelPolicyId, performanceStatus, saleOpenAt, saleCloseAt, MAX_PER_PERSON,
			receiveType, bookingFee, now, now);
		performanceIds.add(performanceId);
		Long scheduleId = jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?) RETURNING id
			""", Long.class, performanceId, startAt, scheduleStatus, now, now);
		Long gradeId = jdbc.queryForObject("""
			INSERT INTO seat_grade (performance_id, name, price, color) VALUES (?, 'R석', 154000, '#E8503A') RETURNING id
			""", Long.class, performanceId);
		List<Long> seatIds = new ArrayList<>();
		for (int i = 0; i < seatCount; i++) {
			seatIds.add(saveSeat(scheduleId, venueSeatIds.get(i), gradeId, "AVAILABLE"));
		}
		return new Show(performanceId, scheduleId, seatIds);
	}

	private Long saveSeat(Long scheduleId, Long venueSeatId, Long gradeId, String status) {
		return jdbc.queryForObject("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, grade_id, price, status, version, updated_at)
			VALUES (?, ?, ?, ?, 154000, ?, 0, ?) RETURNING id
			""", Long.class, scheduleId, venueSeatId, zoneId, gradeId, status, now);
	}

	private record User(Long id, String bearer) {
	}

	/** 실제 가입 API 로 예매자를 만들고 액세스 토큰을 받는다. 이름은 본인인증 값(홍길동)으로 들어간다. */
	private User signup(String name) throws Exception {
		String email = name + "@example.com";
		MvcResult result = postJson(SIGNUP, userSignupBody(email, "ci-" + name))
			.andExpect(status().isCreated())
			.andReturn();
		Long id = jdbc.queryForObject("SELECT id FROM user_member WHERE email = ?", Long.class, email);
		return new User(id, "Bearer " + accessToken(result));
	}

	private ResultActions book(String bearer, Long scheduleId, List<Long> seatIds) throws Exception {
		return mockMvc.perform(post("/api/v1/schedules/" + scheduleId + "/bookings")
			.header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("seatIds", seatIds))));
	}

	/** 성공하면 "OK", 실패하면 에러 코드. 동시성 테스트에서 결과를 모아 세는 데 쓴다. */
	private String bookForResult(String bearer, Long scheduleId, List<Long> seatIds) throws Exception {
		MvcResult result = book(bearer, scheduleId, seatIds).andReturn();
		return result.getResponse().getStatus() == 201 ? "OK" : read(result, "$.code");
	}

	private String seatStatus(Long seatId) {
		return jdbc.queryForObject("SELECT status FROM schedule_seat WHERE id = ?", String.class, seatId);
	}

	private long countOf(String result, List<String> results) {
		return results.stream().filter(result::equals).count();
	}

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
			.andExpect(jsonPath("$.data.seats[0].gradeName").value("R석"))
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

	@Test
	@DisplayName("예매 시점의 취소 규정이 daysFrom 이 큰 구간부터 JSON 으로 복사된다")
	void copiesCancelPolicySnapshot() throws Exception {
		Show show = show(1);
		User user = signup("first");

		book(user.bearer(), show.scheduleId(), show.seatIds()).andExpect(status().isCreated());

		Integer ruleCount = jdbc.queryForObject(
			"SELECT count(*) FROM cancel_policy_rule WHERE policy_id = ?", Integer.class, cancelPolicyId);
		Integer maxDaysFrom = jdbc.queryForObject(
			"SELECT max(days_from) FROM cancel_policy_rule WHERE policy_id = ?", Integer.class, cancelPolicyId);
		String snapshot = jdbc.queryForObject("SELECT cancel_policy_snapshot::text FROM booking", String.class);

		assertThat(objectMapper.readTree(snapshot).size()).isEqualTo(ruleCount);
		assertThat(objectMapper.readTree(snapshot).get(0).get("daysFrom").asInt()).isEqualTo(maxDaysFrom);
		assertThat(objectMapper.readTree(snapshot).get(0).has("feeRate")).isTrue();
		assertThat(objectMapper.readTree(snapshot).get(0).has("cancelable")).isTrue();
	}

	@Test
	@DisplayName("배송만 되는 공연은 수령 방법이 DELIVERY 이고, 예매 수수료가 없으면 0원으로 계산한다")
	void deliveryOnlyAndNoBookingFee() throws Exception {
		Show show = show(1, "PUBLISHED", "ON_SALE", now.plusDays(10), now.minusDays(1), now.plusDays(30), "DELIVERY", null);
		User user = signup("first");

		book(user.bearer(), show.scheduleId(), show.seatIds())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.seats[0].bookingFee").value(0))
			.andExpect(jsonPath("$.data.bookingFeeAmount").value(0))
			.andExpect(jsonPath("$.data.totalAmount").value(154000));

		assertThat(jdbc.queryForObject("SELECT receive_type FROM booking", String.class)).isEqualTo("DELIVERY");
	}

	// ---------------------------------------------------------------- 입력·권한

	@Test
	@DisplayName("토큰 없이 호출하면 401, 판매자·관리자 토큰으로 호출하면 403 이고 아무것도 점유되지 않는다")
	void needsUserRole() throws Exception {
		Show show = show(1);
		String seller = "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.SELLER, "OWNER", 1L));
		String admin = "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.ADMIN, null, null));

		mockMvc.perform(post("/api/v1/schedules/" + show.scheduleId() + "/bookings")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("seatIds", show.seatIds()))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
		book(seller, show.scheduleId(), show.seatIds())
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
		book(admin, show.scheduleId(), show.seatIds())
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));

		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
		assertThat(count("booking")).isZero();
	}

	@Test
	@DisplayName("좌석 번호가 비었거나 중복이면 400 COMMON000 이다")
	void rejectsEmptyOrDuplicatedSeatIds() throws Exception {
		Show show = show(2);
		User user = signup("first");
		Long seat = show.seatIds().get(0);

		book(user.bearer(), show.scheduleId(), List.of())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
		book(user.bearer(), show.scheduleId(), List.of(seat, seat))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));

		assertThat(seatStatus(seat)).isEqualTo("AVAILABLE");
	}

	// ---------------------------------------------------------------- 판매 조건

	@Test
	@DisplayName("없는 회차와 공개되지 않은 공연의 회차는 같은 404 BOOKING004 이다")
	void scheduleNotFound() throws Exception {
		User user = signup("first");
		Show draft = show(1, "DRAFT", "ON_SALE", now.plusDays(10), now.minusDays(1), now.plusDays(30), "BOTH", 2000);
		Show canceled = show(1, "CANCELED", "ON_SALE", now.plusDays(10), now.minusDays(1), now.plusDays(30), "BOTH", 2000);

		for (Long scheduleId : List.of(99_999L, draft.scheduleId(), canceled.scheduleId())) {
			book(user.bearer(), scheduleId, List.of(1L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BOOKING004"));
		}
	}

	@Test
	@DisplayName("판매 기간 밖이거나 회차가 중지·취소됐거나 이미 시작했으면 400 BOOKING002 이다")
	void scheduleNotOnSale() throws Exception {
		User user = signup("first");
		List<Show> shows = List.of(
			show(1, "PUBLISHED", "ON_SALE", now.plusDays(10), now.plusDays(1), now.plusDays(30), "BOTH", 2000),
			show(1, "PUBLISHED", "ON_SALE", now.plusDays(10), now.minusDays(30), now.minusDays(1), "BOTH", 2000),
			show(1, "PUBLISHED", "PAUSED", now.plusDays(10), now.minusDays(1), now.plusDays(30), "BOTH", 2000),
			show(1, "PUBLISHED", "POSTPONED", now.plusDays(10), now.minusDays(1), now.plusDays(30), "BOTH", 2000),
			show(1, "PUBLISHED", "CANCELED", now.plusDays(10), now.minusDays(1), now.plusDays(30), "BOTH", 2000),
			show(1, "PUBLISHED", "ON_SALE", now.minusHours(1), now.minusDays(1), now.plusDays(30), "BOTH", 2000));

		for (Show show : shows) {
			book(user.bearer(), show.scheduleId(), show.seatIds())
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BOOKING002"));
			assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
		}
	}

	@Test
	@DisplayName("없는 좌석과 다른 회차의 좌석은 404 BOOKING005, 판매 제외·보류 좌석은 400 BOOKING003 이다")
	void seatRules() throws Exception {
		Show show = show(1);
		Show other = show(1);
		Long grade = jdbc.queryForObject("SELECT id FROM seat_grade WHERE performance_id = ?", Long.class, show.performanceId());
		Long blocked = saveSeat(show.scheduleId(), venueSeatIds.get(5), grade, "BLOCKED");
		Long reserved = saveSeat(show.scheduleId(), venueSeatIds.get(6), grade, "RESERVED");
		User user = signup("first");

		book(user.bearer(), show.scheduleId(), List.of(other.seatIds().get(0)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING005"));
		book(user.bearer(), show.scheduleId(), List.of(99_999L))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING005"));
		for (Long seat : List.of(blocked, reserved)) {
			book(user.bearer(), show.scheduleId(), List.of(seat))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BOOKING003"));
		}
		assertThat(count("booking")).isZero();
	}

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
	@DisplayName("내가 이미 점유한 좌석을 다시 요청해도 409 BOOKING000 이다")
	void rejectsSeatIAlreadyHold() throws Exception {
		Show show = show(1);
		User user = signup("first");
		book(user.bearer(), show.scheduleId(), show.seatIds()).andExpect(status().isCreated());

		book(user.bearer(), show.scheduleId(), show.seatIds())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING000"));
		assertThat(count("booking")).isEqualTo(1);
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
			VALUES (999, ?, 'F2구역', '9열', '1', 'R석', 154000, 2000, true)
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

	@Test
	@DisplayName("결제 대기 중인 좌석도 한도에 들어가서, 가진 좌석과 합쳐 한도를 넘으면 409 BOOKING001 이다")
	void pendingSeatsCountTowardLimit() throws Exception {
		Show show = show(MAX_PER_PERSON + 1);
		User user = signup("first");
		book(user.bearer(), show.scheduleId(), show.seatIds().subList(0, MAX_PER_PERSON - 1)).andExpect(status().isCreated());

		book(user.bearer(), show.scheduleId(), show.seatIds().subList(MAX_PER_PERSON - 1, MAX_PER_PERSON + 1))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BOOKING001"));
		// 한도 안이면 이어서 점유할 수 있다
		book(user.bearer(), show.scheduleId(), List.of(show.seatIds().get(MAX_PER_PERSON - 1)))
			.andExpect(status().isCreated());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seat WHERE active", Long.class)).isEqualTo(MAX_PER_PERSON);
	}

	@Test
	@DisplayName("취소·만료되어 active=false 가 된 좌석은 한도에서 빠진다")
	void canceledSeatsDoNotCount() throws Exception {
		Show show = show(MAX_PER_PERSON + 1);
		User user = signup("first");
		book(user.bearer(), show.scheduleId(), show.seatIds().subList(0, MAX_PER_PERSON)).andExpect(status().isCreated());
		jdbc.update("UPDATE booking_seat SET active = false");
		jdbc.update("UPDATE schedule_seat SET status = 'AVAILABLE', held_by = NULL, held_until = NULL");

		book(user.bearer(), show.scheduleId(), show.seatIds().subList(0, MAX_PER_PERSON)).andExpect(status().isCreated());
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
	@DisplayName("사용자·공연 락을 빼고 좌석 락만 잡으면 같은 사용자가 한도를 넘긴다 (사용자·공연 락이 필요한 이유)")
	void limitBreaksWithoutUserLock() throws Exception {
		int requests = 10;
		Show show = show(requests);
		CyclicBarrier allChecked = new CyclicBarrier(requests);
		Long userId = 999L;

		List<String> results = runConcurrently(requests, i -> () -> {
			Long seatId = show.seatIds().get(i);
			try {
				lockExecutor.execute(List.of("seat:" + seatId), Duration.ofSeconds(10), () ->
					new TransactionTemplate(transactionManager).execute(status -> {
						long owned = bookingService.countActiveSeats(userId, show.performanceId());
						if (owned + 1 > MAX_PER_PERSON) {
							throw new BusinessException(BookingExceptionCode.MAX_PER_PERSON_EXCEEDED);
						}
						// 모두 한도를 확인한 뒤에 만든다. 서로 다른 좌석의 락은 겹치지 않아서 이 확인을 막아 주는 것이 없다
						awaitAll(allChecked);
						LocalDateTime start = LocalDateTime.now();
						scheduleSeatService.holdSeats(show.scheduleId(), Set.of(seatId), userId, start, start.plusMinutes(7));
						bookingService.create(new BookingCreateCommand(userId, show.scheduleId(), show.performanceId(),
							ReceiveMethod.ONSITE, "홍길동", "01012345678", "user@example.com", 0,
							List.of(new BookingCreateCommand.SeatLine(seatId, "F2구역", "9열", "1", "R석", 154000)),
							List.of(), LocalDate.now(), start.plusMinutes(7)));
						return "OK";
					}));
				return "OK";
			} catch (BusinessException e) {
				return e.getExceptionCode().getCode();
			}
		});

		assertThat(countOf("OK", results)).isGreaterThan(MAX_PER_PERSON);
	}

	private static void awaitAll(CyclicBarrier barrier) {
		try {
			barrier.await(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
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
