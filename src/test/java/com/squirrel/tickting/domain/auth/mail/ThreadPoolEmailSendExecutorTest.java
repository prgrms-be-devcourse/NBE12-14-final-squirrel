package com.squirrel.tickting.domain.auth.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 발송 스레드와 대기 수에 상한이 있는 실행기. 스프링 없이 실제 스레드로 확인한다.
 * 대기가 가득 찼을 때 접수를 거절하는 것(= 몰려도 서버 메모리와 유효 시간이 무한정 늘지 않는 것)과
 * 정상 종료 때 대기 중인 발송을 마저 보내는 것을 본다.
 */
class ThreadPoolEmailSendExecutorTest {

	@Test
	@DisplayName("발송 스레드와 대기가 모두 차면 접수를 거절하고, 자리가 나면 다시 받는다")
	void rejectsWhenQueueIsFull() throws Exception {
		ThreadPoolEmailSendExecutor executor = new ThreadPoolEmailSendExecutor(1, 1);
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger done = new AtomicInteger();
		Runnable blocker = () -> {
			running.countDown();
			await(release);
			done.incrementAndGet();
		};
		try {
			executor.execute(blocker);
			assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
			executor.execute(done::incrementAndGet);
			assertThat(executor.queueSize()).isEqualTo(1);

			assertThatThrownBy(() -> executor.execute(done::incrementAndGet))
				.isInstanceOf(RejectedExecutionException.class);

			release.countDown();
			executor.shutdown();
			assertThat(done.get()).isEqualTo(2);
		} finally {
			release.countDown();
			executor.shutdown();
		}
	}

	@Test
	@DisplayName("정상 종료할 때 대기 중이던 발송을 마저 실행한다")
	void shutdownDrainsQueuedTasks() throws Exception {
		ThreadPoolEmailSendExecutor executor = new ThreadPoolEmailSendExecutor(1, 5);
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger done = new AtomicInteger();
		executor.execute(() -> {
			running.countDown();
			await(release);
			done.incrementAndGet();
		});
		assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
		executor.execute(done::incrementAndGet);
		executor.execute(done::incrementAndGet);

		release.countDown();
		executor.shutdown();

		assertThat(done.get()).isEqualTo(3);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
