package com.squirrel.tickting.domain.auth.mail;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 발송 스레드 수와 대기 수에 상한이 있는 실행기. 서버마다 따로 센다.
 *
 * <p>대기에 상한이 없으면 몰림이 서버 메모리로 옮겨 갈 뿐이고, 줄이 길어져 유효 시간(5분)이 지난 뒤에 메일이 가기도 한다.
 * 그래서 가득 차면 접수하지 않고 거절한다. (RejectedExecutionException)
 *
 * <p>대기 중인 발송은 서버 메모리에 있지만, 인증번호와 횟수의 원본은 Redis 에 있다. 서버가 갑자기 죽어 대기 중이던 메일이 사라져도
 * 사용자는 재발송 간격이 지난 뒤 다시 받으면 된다. 정상 종료할 때는 대기 중인 발송을 마저 보내고 끈다.
 */
public class ThreadPoolEmailSendExecutor implements EmailSendExecutor {

	/** 정상 종료할 때 대기 중인 발송이 끝나기를 기다리는 최대 시간 */
	private static final long SHUTDOWN_WAIT_SECONDS = 10;

	private final ThreadPoolExecutor executor;

	ThreadPoolEmailSendExecutor(int threads, int queueCapacity) {
		// 스레드를 threads 개까지 먼저 만들고, 그다음부터 대기열에 쌓는다. 대기열이 차면 AbortPolicy 가 거절한다.
		this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(queueCapacity),
			Thread.ofPlatform().name("email-send-", 0).factory(),
			new ThreadPoolExecutor.AbortPolicy());
	}

	@Override
	public void execute(Runnable task) {
		executor.execute(task);
	}

	/** 지금 발송을 기다리는 작업 수. 지표로 남긴다. */
	public int queueSize() {
		return executor.getQueue().size();
	}

	/** 스프링이 앱을 끌 때 부른다. 새 작업은 받지 않고, 대기 중인 발송은 제한 시간 안에서 마저 보낸다. */
	public void shutdown() {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
