package com.squirrel.tickting.global.security;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * BCrypt 를 서버 한 대에서 동시에 maxConcurrent 개까지만 계산하게 막는다.
 *
 * BCrypt 한 번이 CPU 를 수십 ms 쓰므로 로그인·가입이 몰리면 CPU 가 먼저 찬다.
 * 막지 않으면 요청이 모두 함께 느려져 60초를 넘겨 실패하고, 톰캣 스레드가 다 차서 다른 API 까지 멈춘다.
 * 넘친 요청은 잠깐만 기다리고 503 으로 돌려보내서, 들어온 요청은 빨리 끝나고 다른 API 는 계속 처리되게 한다.
 *
 * 개수는 Redis 가 아니라 서버 메모리에서 센다. 지키려는 것이 그 서버의 CPU 라서다.
 * (전체 개수로 세면 요청이 한 서버에 쏠렸을 때 그 서버가 다 떠안는다)
 * 로그인 세션처럼 어느 서버로 가도 같아야 하는 사용자 상태가 아니라 톰캣 스레드 수·DB 연결 풀과 같은 서버 자신의 부하 제한이다.
 */
class ConcurrencyLimitedPasswordEncoder implements PasswordEncoder {

	private final PasswordEncoder delegate;
	private final Semaphore permits;
	private final long waitNanos;

	ConcurrencyLimitedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent, Duration waitTimeout) {
		this.delegate = delegate;
		// 공정 모드: 늦게 온 요청이 먼저 자리를 차지해 앞의 요청이 계속 밀리는 일을 막는다
		this.permits = new Semaphore(maxConcurrent, true);
		this.waitNanos = waitTimeout.toNanos();
	}

	@Override
	public String encode(CharSequence rawPassword) {
		return limited(() -> delegate.encode(rawPassword));
	}

	@Override
	public boolean matches(CharSequence rawPassword, String encodedPassword) {
		return limited(() -> delegate.matches(rawPassword, encodedPassword));
	}

	/** 해시를 계산하지 않고 강도만 비교해서 막지 않는다. */
	@Override
	public boolean upgradeEncoding(String encodedPassword) {
		return delegate.upgradeEncoding(encodedPassword);
	}

	private <T> T limited(Supplier<T> hashing) {
		acquire();
		try {
			return hashing.get();
		} finally {
			permits.release();
		}
	}

	private void acquire() {
		try {
			if (!permits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
				throw new BusinessException(CommonExceptionCode.SERVER_BUSY);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BusinessException(CommonExceptionCode.SERVER_BUSY);
		}
	}
}
