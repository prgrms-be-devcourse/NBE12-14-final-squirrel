package com.squirrel.tickting.global.lock;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis 분산락을 잡고 작업을 실행한다. 좌석 점유처럼 서버 여러 대가 같은 자원을 동시에 바꾸는 흐름에 쓴다.
 *
 * <p>지켜야 할 규칙 세 가지
 * <ol>
 *   <li><b>락을 먼저 잡고, 그 안에서 트랜잭션을 열고 닫는다.</b> 락은 트랜잭션이 커밋된 <b>뒤에</b> 풀려야 한다.
 *       커밋 전에 풀리면 다음 요청이 아직 반영되지 않은 값을 읽어 중복 처리가 생긴다.
 *       그래서 이미 트랜잭션이 열려 있으면 거부한다. (호출하는 쪽에서 {@code TransactionTemplate} 으로 action 안에서 연다)</li>
 *   <li><b>여러 락은 항상 같은 순서(키 정렬)로 잡는다.</b> 서로 다른 순서로 잡으면 두 요청이 서로를 기다리는 데드락이 생긴다.</li>
 *   <li><b>기다리는 시간은 짧게 둔다.</b> 오래 기다리게 하면 인기 좌석에 요청이 쌓여 서버 스레드가 모자란다.
 *       시간 안에 못 잡으면 기다리지 않고 바로 실패({@code COMMON004})시킨다.</li>
 * </ol>
 *
 * <p>Redis 락만 믿지 않는다. 락이 풀리는 순간이나 Redis 장애 때를 대비해 DB 의 상태 조건부 갱신과
 * UNIQUE 인덱스(uq_booking_seat_active 등)를 마지막 방어선으로 함께 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockExecutor {

	private static final String KEY_PREFIX = "tickting:lock:";

	private final RedissonClient redissonClient;

	/**
	 * @param keys     잡을 락의 이름들. 정렬해서 잡는다. (예: "seat:101", "user-performance:7:3")
	 * @param waitTime 전체 락을 잡기 위해 기다릴 수 있는 최대 시간. 넘으면 COMMON004
	 * @param action   락을 잡은 상태에서 실행할 일. 트랜잭션이 필요하면 이 안에서 연다
	 */
	public <T> T execute(Collection<String> keys, Duration waitTime, Supplier<T> action) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException(
				"트랜잭션 안에서는 분산락을 잡을 수 없다. 락을 먼저 잡고 그 안에서 트랜잭션을 열어야 락이 커밋 뒤에 풀린다.");
		}

		List<RLock> acquired = new ArrayList<>();
		try {
			long deadline = System.nanoTime() + waitTime.toNanos();
			// 정렬된 순서로 잡아서 요청마다 순서가 달라 생기는 데드락을 막는다
			for (String key : new TreeSet<>(keys)) {
				RLock lock = redissonClient.getLock(KEY_PREFIX + key);
				long remaining = Math.max(0, deadline - System.nanoTime());
				if (!lock.tryLock(remaining, TimeUnit.NANOSECONDS)) {
					throw new BusinessException(CommonExceptionCode.LOCK_ACQUISITION_FAILED);
				}
				acquired.add(lock);
			}
			return action.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BusinessException(CommonExceptionCode.LOCK_ACQUISITION_FAILED);
		} finally {
			release(acquired);
		}
	}

	/** 잡은 순서의 반대로 푼다. 풀다가 실패해도(이미 만료 등) 나머지 락은 계속 푼다. */
	private void release(List<RLock> acquired) {
		for (int i = acquired.size() - 1; i >= 0; i--) {
			RLock lock = acquired.get(i);
			try {
				if (lock.isHeldByCurrentThread()) {
					lock.unlock();
				}
			} catch (RuntimeException e) {
				log.warn("분산락 해제에 실패했다. 락 이름: {}", lock.getName(), e);
			}
		}
	}
}
