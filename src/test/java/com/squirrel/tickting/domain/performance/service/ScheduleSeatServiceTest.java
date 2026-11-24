package com.squirrel.tickting.domain.performance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 좌석 점유. 실제 PostgreSQL·Redis(Testcontainers)에서 같은 좌석을 동시에 잡아도 한 명만 성공하는지 확인한다. */
class ScheduleSeatServiceTest extends IntegrationTestSupport {

	private static final Long SCHEDULE_ID = 1L;
	private static final Duration LOCK_WAIT = Duration.ofSeconds(10);

	@Autowired
	ScheduleSeatService scheduleSeatService;

	@Autowired
	DistributedLockExecutor lockExecutor;

	@Autowired
	PlatformTransactionManager transactionManager;

	@BeforeEach
	void resetSeats() {
		jdbc.execute("TRUNCATE TABLE schedule_seat RESTART IDENTITY");
	}

	/** 좌석 한 개를 넣고 번호를 돌려준다. */
	private Long insertSeat(Long scheduleId, long venueSeatId, String status) {
		return jdbc.queryForObject("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, grade_id, price, status, version, updated_at)
			VALUES (?, ?, 1, 1, 154000, ?, 0, now())
			RETURNING id
			""", Long.class, scheduleId, venueSeatId, status);
	}

	private String statusOf(Long seatId) {
		return jdbc.queryForObject("SELECT status FROM schedule_seat WHERE id = ?", String.class, seatId);
	}

	private Long heldByOf(Long seatId) {
		return jdbc.queryForObject("SELECT held_by FROM schedule_seat WHERE id = ?", Long.class, seatId);
	}

	private List<ScheduleSeat> hold(Set<Long> seatIds, Long userId) {
		LocalDateTime now = LocalDateTime.now();
		return scheduleSeatService.holdSeats(SCHEDULE_ID, seatIds, userId, now, now.plusMinutes(7));
	}

	/** 락 이름 규칙(part4 4-7)대로 좌석마다 락을 잡고, 그 안에서 트랜잭션을 열어 점유한다. */
	private void holdWithLock(Set<Long> seatIds, Long userId) {
		List<String> keys = seatIds.stream().map(id -> "seat:" + id).toList();
		lockExecutor.execute(keys, LOCK_WAIT, () -> new TransactionTemplate(transactionManager).execute(status -> hold(seatIds, userId)));
	}

	private static String codeOf(Throwable e) {
		return ((BusinessException) e).getExceptionCode().getCode();
	}

	// ---------------------------------------------------------------- 점유 규칙

	@Test
	@DisplayName("점유하면 좌석이 HELD 가 되고 점유자와 만료 시각이 기록된다")
	void holdSetsStatusAndOwner() {
		Long seat = insertSeat(SCHEDULE_ID, 1, "AVAILABLE");
		LocalDateTime now = LocalDateTime.now();

		List<ScheduleSeat> held = scheduleSeatService.holdSeats(SCHEDULE_ID, Set.of(seat), 7L, now, now.plusMinutes(7));

		assertThat(held).hasSize(1);
		assertThat(held.get(0).getPrice()).isEqualTo(154000);
		assertThat(statusOf(seat)).isEqualTo("HELD");
		assertThat(heldByOf(seat)).isEqualTo(7L);
		LocalDateTime heldUntil = jdbc.queryForObject("SELECT held_until FROM schedule_seat WHERE id = ?",
			LocalDateTime.class, seat);
		assertThat(heldUntil).isAfter(now.plusMinutes(6));
	}

	@Test
	@DisplayName("이미 점유된 좌석이 하나라도 섞여 있으면 전체가 거절되고 나머지 좌석도 점유되지 않는다")
	void rejectsAllWhenOneIsAlreadyHeld() {
		Long free = insertSeat(SCHEDULE_ID, 1, "AVAILABLE");
		Long taken = insertSeat(SCHEDULE_ID, 2, "HELD");

		assertThatThrownBy(() -> hold(Set.of(free, taken), 7L))
			.isInstanceOf(BusinessException.class)
			.satisfies(e -> assertThat(codeOf(e)).isEqualTo("BOOKING000"));

		assertThat(statusOf(free)).isEqualTo("AVAILABLE");
	}

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("20명이 같은 좌석을 동시에 점유해도 한 명만 성공하고 나머지는 BOOKING000 이다")
	void onlyOneWinsOnSameSeatWithLock() throws Exception {
		Long seat = insertSeat(SCHEDULE_ID, 1, "AVAILABLE");

		List<String> results = runConcurrently(20, i -> () -> {
			try {
				holdWithLock(Set.of(seat), 100L + i);
				return "OK:" + (100L + i);
			} catch (BusinessException e) {
				return e.getExceptionCode().getCode();
			}
		});

		List<String> winners = results.stream().filter(r -> r.startsWith("OK")).toList();
		assertThat(winners).hasSize(1);
		assertThat(results.stream().filter(r -> r.equals("BOOKING000")).count()).isEqualTo(19);
		assertThat(heldByOf(seat)).isEqualTo(Long.parseLong(winners.get(0).substring(3)));
	}

	@Test
	@DisplayName("락이 없어도 상태 조건이 있는 UPDATE 가 중복 점유를 막는다 (Redis 가 뚫렸을 때의 안전장치)")
	void conditionalUpdateStopsDoubleHoldWithoutLock() throws Exception {
		Long seat = insertSeat(SCHEDULE_ID, 1, "AVAILABLE");
		AtomicInteger success = new AtomicInteger();

		runConcurrently(20, i -> () -> {
			try {
				new TransactionTemplate(transactionManager).execute(status -> hold(Set.of(seat), 100L + i));
				success.incrementAndGet();
			} catch (BusinessException ignored) {
				// 졌다
			}
			return null;
		});

		assertThat(success.get()).isEqualTo(1);
		assertThat(statusOf(seat)).isEqualTo("HELD");
	}

	@Test
	@DisplayName("겹치는 좌석을 서로 반대 순서로 요청해도 데드락 없이 한 명만 성공하고 아무도 일부만 잡지 않는다")
	void overlappingRequestsInOppositeOrderDoNotDeadlock() throws Exception {
		Long a = insertSeat(SCHEDULE_ID, 1, "AVAILABLE");
		Long b = insertSeat(SCHEDULE_ID, 2, "AVAILABLE");

		List<String> results = runConcurrently(2, i -> () -> {
			Set<Long> seatIds = i == 0 ? new LinkedHashSet<>(List.of(a, b)) : new LinkedHashSet<>(List.of(b, a));
			try {
				holdWithLock(seatIds, 100L + i);
				return "OK:" + (100L + i);
			} catch (BusinessException e) {
				return e.getExceptionCode().getCode();
			}
		});

		List<String> winners = results.stream().filter(r -> r.startsWith("OK")).toList();
		assertThat(winners).hasSize(1);
		Long winner = Long.parseLong(winners.get(0).substring(3));
		assertThat(heldByOf(a)).isEqualTo(winner);
		assertThat(heldByOf(b)).isEqualTo(winner);
	}
}
