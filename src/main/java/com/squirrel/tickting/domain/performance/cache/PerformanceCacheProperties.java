package com.squirrel.tickting.domain.performance.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예매자 공연 조회 캐시 설정. 값은 application.yaml 의 app.performance.cache 에 있다. (모두 임시값)
 *
 * @param enabled         false 면 모든 조회가 DB 에서 읽는다. 테스트는 데이터를 바로 바꿔 확인하므로 기본으로 끈다
 * @param listTtl         공연 목록
 * @param detailTtl       공연 상세
 * @param zoneSummaryTtl  구역 요약. 잔여석이 들어 있어서 가장 짧게 둔다
 */
@ConfigurationProperties(prefix = "app.performance.cache")
public record PerformanceCacheProperties(boolean enabled, Duration listTtl, Duration detailTtl,
	Duration zoneSummaryTtl) {

	/** 꺼져 있으면 0 을 돌려줘서 캐시 없이 읽게 한다. */
	Duration effective(Duration ttl) {
		return enabled ? ttl : Duration.ZERO;
	}
}
