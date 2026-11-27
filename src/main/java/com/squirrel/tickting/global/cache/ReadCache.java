package com.squirrel.tickting.global.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 짧은 시간 동안만 쓰는 읽기 전용 응답 캐시. 서버가 여러 대여도 같은 Redis 를 보므로 한 서버가 채우면 다른 서버도 쓴다.
 *
 * 규칙은 두 가지다.
 *   - 값이 조금 낡아도 되는 공개 조회에만 쓴다. 만료로만 지우고(무효화 없음) 쓰는 쪽이 TTL 만큼의 지연을 감수한다.
 *     좌석 상태·내 예매·결제처럼 틀린 값이 곧바로 문제가 되는 것은 캐시하지 않는다.
 *   - 캐시는 최적화일 뿐이라 Redis 가 느리거나 값이 깨져도 원본에서 읽어서 응답한다. 캐시 때문에 조회가 실패하면 안 된다.
 *
 * 값은 응답 객체를 JSON 으로 저장한다. 트랜잭션 밖(컨트롤러 바로 아래)에서 불러야 캐시 적중 때 DB 연결을 빌리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadCache {

	private static final String KEY_PREFIX = "tickting:cache:";
	/** Redis 장애 중에는 요청마다 실패하므로 경고 로그는 이 간격으로만 남긴다. */
	private static final long WARN_INTERVAL_MILLIS = 10_000;

	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;

	private final AtomicLong lastWarnAt = new AtomicLong();

	/**
	 * 캐시에 있으면 그 값을, 없으면 loader 로 읽어서 ttl 동안 저장하고 돌려준다.
	 * ttl 이 0 이하면 캐시를 쓰지 않고 loader 만 부른다. (예외는 저장하지 않고 그대로 던진다. 404 같은 응답은 캐시되지 않는다)
	 *
	 * @param name 지표(read.cache{name=...})와 키 앞부분에 쓰는 종류 이름
	 * @param key  종류 안에서 요청을 구분하는 값. 사용자 입력이 들어가면 호출하는 쪽이 길이·종류를 제한한다
	 */
	public <T> T getOrLoad(String name, String key, Duration ttl, TypeReference<T> type, Supplier<T> loader) {
		if (ttl.isZero() || ttl.isNegative()) {
			return loader.get();
		}
		String redisKey = KEY_PREFIX + name + ":" + key;

		T cached = read(name, redisKey, type);
		if (cached != null) {
			count(name, "hit");
			return cached;
		}

		T loaded = loader.get();
		count(name, "miss");
		if (loaded != null) {
			write(name, redisKey, loaded, ttl);
		}
		return loaded;
	}

	/** 저장한 값을 만료 전에 지운다. 지우지 못해도 요청은 성공시키고, 값은 만료되면 사라진다. */
	public void evict(String name, String key) {
		try {
			redis.delete(KEY_PREFIX + name + ":" + key);
		} catch (RuntimeException e) {
			fail(name, "삭제", e);
		}
	}

	private <T> T read(String name, String redisKey, TypeReference<T> type) {
		try {
			String json = redis.opsForValue().get(redisKey);
			return json == null ? null : objectMapper.readValue(json, type);
		} catch (RuntimeException e) {
			// 깨진 값이면 원본에서 읽은 값이 곧 덮어쓴다.
			fail(name, "읽기", e);
			return null;
		}
	}

	private void write(String name, String redisKey, Object value, Duration ttl) {
		try {
			redis.opsForValue().set(redisKey, objectMapper.writeValueAsString(value), ttl);
		} catch (RuntimeException e) {
			fail(name, "저장", e);
		}
	}

	private void fail(String name, String action, RuntimeException e) {
		count(name, "error");
		long now = System.currentTimeMillis();
		long last = lastWarnAt.get();
		if (now - last >= WARN_INTERVAL_MILLIS && lastWarnAt.compareAndSet(last, now)) {
			log.warn("읽기 캐시 {} 실패. 원본에서 읽는다. ({}: {})", action, name, e.toString());
		}
	}

	private void count(String name, String result) {
		meterRegistry.counter("read.cache", "name", name, "result", result).increment();
	}
}
