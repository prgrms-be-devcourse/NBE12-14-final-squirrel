package com.squirrel.tickting.global.config;

import lombok.RequiredArgsConstructor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 분산락용 Redisson 클라이언트. 세션과 같은 Redis 에 붙는다.
 *
 * 접속 정보는 spring.data.redis.* 속성이 아니라 DataRedisConnectionDetails 에서 읽는다.
 * 테스트의 Testcontainers 처럼 Spring Boot 가 접속 정보를 따로 주는 경우에도 같은 주소로 붙게 하려는 것이다.
 *
 * TODO: ElastiCache(클러스터·TLS)로 옮길 때 단일 서버 설정을 바꾼다.
 */
@Configuration
@RequiredArgsConstructor
public class RedissonConfig {

	/** 락을 잡은 서버가 죽으면 이 시간 뒤에 락이 풀린다. 살아 있는 동안에는 Redisson 이 자동으로 연장한다. */
	private static final long LOCK_WATCHDOG_TIMEOUT_MILLIS = 10_000;

	private final DataRedisConnectionDetails connectionDetails;

	@Bean(destroyMethod = "shutdown")
	public RedissonClient redissonClient() {
		DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
		if (standalone == null) {
			throw new IllegalStateException("분산락은 아직 단일 Redis 서버 설정만 지원한다.");
		}

		Config config = new Config();
		config.setLockWatchdogTimeout(LOCK_WATCHDOG_TIMEOUT_MILLIS);
		config.useSingleServer()
			.setAddress("redis://" + standalone.getHost() + ":" + standalone.getPort())
			.setDatabase(standalone.getDatabase())
			.setUsername(connectionDetails.getUsername())
			.setPassword(connectionDetails.getPassword());
		return Redisson.create(config);
	}
}
