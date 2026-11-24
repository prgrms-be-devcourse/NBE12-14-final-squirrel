package com.squirrel.tickting.global.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Redis 분산락 실행기. 실제 Redis(Testcontainers)와 여러 스레드로 락의 규칙이 지켜지는지 확인한다. */
class DistributedLockExecutorTest extends IntegrationTestSupport {

	private static final Duration WAIT = Duration.ofSeconds(10);

	@Autowired
	DistributedLockExecutor lockExecutor;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void createCounterTable() {
		jdbc.execute("CREATE TABLE IF NOT EXISTS lock_test_counter (id INT PRIMARY KEY, val INT NOT NULL)");
		jdbc.execute("TRUNCATE TABLE lock_test_counter");
		jdbc.update("INSERT INTO lock_test_counter (id, val) VALUES (1, 0)");
	}

	@AfterEach
	void dropCounterTable() {
		jdbc.execute("DROP TABLE IF EXISTS lock_test_counter");
	}

	/** 스레드를 모두 준비시킨 뒤 동시에 출발시킨다. 하나라도 예외가 나면 그대로 던진다. */
	private void runConcurrently(int threads, Callable<Void> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch ready = new CountDownLatch(threads);
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Void>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					return task.call();
				}));
			}
			ready.await();
			start.countDown();
			for (Future<Void> future : futures) {
				future.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/** 읽고 → 잠깐 쉬고 → 1 올려 쓰는 한 건의 트랜잭션. 락이 없으면 서로의 갱신을 덮어쓴다. */
	private void incrementInTransaction() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			Integer current = jdbc.queryForObject("SELECT val FROM lock_test_counter WHERE id = 1", Integer.class);
			sleep(3);
			jdbc.update("UPDATE lock_test_counter SET val = ? WHERE id = 1", current + 1);
		});
	}

	private int counter() {
		return jdbc.queryForObject("SELECT val FROM lock_test_counter WHERE id = 1", Integer.class);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ---------------------------------------------------------------- 상호 배제

	@Test
	@DisplayName("같은 키는 동시에 한 요청만 들어가고, 모든 요청이 빠짐없이 처리된다")
	void sameKeyIsMutuallyExclusive() throws Exception {
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger maxInside = new AtomicInteger();
		AtomicInteger done = new AtomicInteger();

		runConcurrently(20, () -> {
			lockExecutor.execute(List.of("seat:1"), WAIT, () -> {
				int now = inside.incrementAndGet();
				maxInside.accumulateAndGet(now, Math::max);
				sleep(5);
				inside.decrementAndGet();
				done.incrementAndGet();
				return null;
			});
			return null;
		});

		assertThat(maxInside.get()).isEqualTo(1);
		assertThat(done.get()).isEqualTo(20);
	}

	@Test
	@DisplayName("서로 다른 키는 서로 기다리지 않고 동시에 실행된다")
	void differentKeysRunInParallel() throws Exception {
		CyclicBarrier bothInside = new CyclicBarrier(2);
		AtomicInteger index = new AtomicInteger();

		runConcurrently(2, () -> {
			String key = "seat:" + index.incrementAndGet();
			lockExecutor.execute(List.of(key), WAIT, () -> {
				try {
					// 두 요청이 동시에 안에 들어와야 통과한다. 하나씩만 실행되면 시간 초과로 실패한다
					bothInside.await(5, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new IllegalStateException("서로 다른 키가 동시에 실행되지 않았다", e);
				}
				return null;
			});
			return null;
		});
	}

	// ---------------------------------------------------------------- 데드락

	@Test
	@DisplayName("여러 락을 서로 반대 순서로 요청해도 정렬해서 잡으므로 데드락 없이 모두 끝난다")
	void noDeadlockWhenKeysAreRequestedInOppositeOrder() throws Exception {
		AtomicInteger index = new AtomicInteger();
		AtomicInteger done = new AtomicInteger();

		runConcurrently(2, () -> {
			List<String> keys = index.getAndIncrement() == 0 ? List.of("seat:1", "seat:2") : List.of("seat:2", "seat:1");
			for (int i = 0; i < 50; i++) {
				lockExecutor.execute(keys, WAIT, () -> {
					done.incrementAndGet();
					return null;
				});
			}
			return null;
		});

		assertThat(done.get()).isEqualTo(100);
	}

	// ---------------------------------------------------------------- 실패와 해제

	@Test
	@DisplayName("기다리는 시간 안에 못 잡으면 COMMON004 로 바로 실패하고, 그때까지 잡은 락은 풀린다")
	void failsFastAndReleasesAcquiredLocks() throws Exception {
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService holder = Executors.newSingleThreadExecutor();
		try {
			// 다른 스레드가 "b" 를 오래 잡고 있다
			Future<Void> held = holder.submit(() -> {
				lockExecutor.execute(List.of("b"), WAIT, () -> {
					holding.countDown();
					try {
						release.await(20, TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					return null;
				});
				return null;
			});
			assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

			long started = System.nanoTime();
			// 정렬하면 "a" 를 먼저 잡고 "b" 에서 막힌다
			assertThatThrownBy(() -> lockExecutor.execute(List.of("b", "a"), Duration.ofMillis(300), () -> null))
				.isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getExceptionCode().getCode()).isEqualTo("COMMON004"));
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

			assertThat(elapsedMillis).isLessThan(3_000);
			// 먼저 잡았던 "a" 는 풀려 있어야 한다
			assertThat(lockExecutor.execute(List.of("a"), Duration.ofMillis(300), () -> "ok")).isEqualTo("ok");

			release.countDown();
			held.get(10, TimeUnit.SECONDS);
		} finally {
			release.countDown();
			holder.shutdownNow();
		}
	}

	@Test
	@DisplayName("작업에서 예외가 나도 락은 풀리고 예외는 그대로 전달된다")
	void releasesLockWhenActionThrows() {
		assertThatThrownBy(() -> lockExecutor.execute(List.of("seat:1"), WAIT, () -> {
			throw new IllegalArgumentException("작업 실패");
		})).isInstanceOf(IllegalArgumentException.class).hasMessage("작업 실패");

		assertThat(lockExecutor.execute(List.of("seat:1"), Duration.ofMillis(300), () -> "ok")).isEqualTo("ok");
	}

	@Test
	@DisplayName("트랜잭션이 이미 열려 있으면 거부한다 (락은 커밋 뒤에 풀려야 해서)")
	void rejectsInsideTransaction() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> template.execute(status -> lockExecutor.execute(List.of("seat:1"), WAIT, () -> null)))
			.isInstanceOf(IllegalStateException.class);
	}

	// ---------------------------------------------------------------- 커밋 뒤에 풀리는지, Redis 에 실제로 저장되는지

	@Test
	@DisplayName("락 안에서 트랜잭션을 열면 20개 요청이 값을 하나도 잃지 않고 20 이 된다")
	void counterIsExactWithLock() throws Exception {
		runConcurrently(20, () -> {
			lockExecutor.execute(List.of("counter:1"), WAIT, () -> {
				incrementInTransaction();
				return null;
			});
			return null;
		});

		assertThat(counter()).isEqualTo(20);
	}

	@Test
	@DisplayName("같은 작업을 락 없이 하면 서로의 갱신을 덮어써서 값이 20 보다 작아진다 (락이 필요한 이유)")
	void counterLosesUpdatesWithoutLock() throws Exception {
		runConcurrently(20, () -> {
			incrementInTransaction();
			return null;
		});

		assertThat(counter()).isLessThan(20);
	}
}
