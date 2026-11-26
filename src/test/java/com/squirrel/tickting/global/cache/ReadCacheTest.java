package com.squirrel.tickting.global.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 읽기 캐시는 최적화일 뿐이라 Redis 가 죽어도 조회가 실패하면 안 된다. 닿지 않는 주소로 Redis 에 붙여서 확인한다.
 * (정상 동작은 UserPerformanceCacheApiTest 가 실제 Redis 로 확인한다)
 */
class ReadCacheTest {

	private LettuceConnectionFactory connectionFactory;
	private ReadCache readCache;

	@BeforeEach
	void setUp() {
		// 1번 포트는 아무도 열지 않아서 바로 연결이 거절된다.
		connectionFactory = new LettuceConnectionFactory("127.0.0.1", 1);
		connectionFactory.afterPropertiesSet();
		readCache = new ReadCache(new StringRedisTemplate(connectionFactory), JsonMapper.builder().build(),
			new SimpleMeterRegistry());
	}

	@AfterEach
	void tearDown() {
		connectionFactory.destroy();
	}

	@Test
	@DisplayName("Redis 에 닿지 않아도 원본에서 읽어서 응답한다")
	void loadsFromSourceWhenRedisIsDown() {
		AtomicInteger loads = new AtomicInteger();

		String value = readCache.getOrLoad("test", "key", Duration.ofSeconds(5), new TypeReference<String>() {
		}, () -> "원본 " + loads.incrementAndGet());

		assertThat(value).isEqualTo("원본 1");
	}

	@Test
	@DisplayName("원본이 던진 예외는 캐시 실패와 섞이지 않고 그대로 나간다")
	void sourceExceptionIsNotSwallowed() {
		assertThatThrownBy(() -> readCache.getOrLoad("test", "key", Duration.ofSeconds(5), new TypeReference<String>() {
		}, () -> {
			throw new IllegalStateException("없는 공연");
		})).isInstanceOf(IllegalStateException.class).hasMessage("없는 공연");
	}
}
