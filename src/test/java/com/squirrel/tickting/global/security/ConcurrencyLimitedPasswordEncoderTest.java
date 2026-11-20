package com.squirrel.tickting.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt 동시 계산 상한. 몰릴 때 상한을 넘겨 CPU 를 다 쓰지 않는지, 넘친 요청이 오래 붙잡히지 않고 503 으로 끝나는지 본다.
 * 진짜 BCrypt 는 시간이 들쭉날쭉해서 계산 중인 상태를 붙잡아 둘 수 있는 가짜 인코더로 확인한다.
 */
class ConcurrencyLimitedPasswordEncoderTest {

	/** 풀어 주기 전까지 계산이 끝나지 않는 인코더. 동시에 몇 개가 계산 중이었는지 센다. */
	static class BlockingEncoder implements PasswordEncoder {
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicInteger running = new AtomicInteger();
		final AtomicInteger maxRunning = new AtomicInteger();

		@Override
		public String encode(CharSequence rawPassword) {
			matches(rawPassword, "");
			return "hash";
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				running.decrementAndGet();
			}
			return true;
		}
	}

	@Test
	@DisplayName("동시에 10개가 와도 2개까지만 계산하고, 기다리는 시간을 넘긴 나머지는 503(COMMON005)으로 바로 끝난다. 계산이 끝나면 자리가 반납돼 다시 받는다")
	void limitsConcurrentHashing() throws Exception {
		BlockingEncoder slow = new BlockingEncoder();
		PasswordEncoder encoder = new ConcurrencyLimitedPasswordEncoder(slow, 2, Duration.ofMillis(200));
		ExecutorService pool = Executors.newFixedThreadPool(10);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				results.add(pool.submit(() -> encoder.matches("pw", "hash")));
			}

			int busy = 0;
			List<Future<Boolean>> admitted = new ArrayList<>();
			for (Future<Boolean> result : results) {
				try {
					result.get(1, TimeUnit.SECONDS);
					admitted.add(result);
				} catch (TimeoutException stillHashing) {
					admitted.add(result);
				} catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(BusinessException.class);
					assertThat(((BusinessException) e.getCause()).getExceptionCode()).isEqualTo(CommonExceptionCode.SERVER_BUSY);
					busy++;
				}
			}
			assertThat(busy).isEqualTo(8);
			assertThat(slow.maxRunning.get()).isEqualTo(2);

			slow.release.countDown();
			for (Future<Boolean> result : admitted) {
				assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
			}
			assertThat(encoder.encode("pw")).isEqualTo("hash");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("계산 중 예외가 나도 자리가 반납돼 다음 요청이 막히지 않는다")
	void releasesPermitOnFailure() {
		PasswordEncoder failing = new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				throw new IllegalStateException("boom");
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				throw new IllegalStateException("boom");
			}
		};
		PasswordEncoder encoder = new ConcurrencyLimitedPasswordEncoder(failing, 1, Duration.ofMillis(100));

		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> encoder.matches("pw", "hash")).isInstanceOf(IllegalStateException.class);
		}
	}
}
