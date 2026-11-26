package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 좌석 점유 요청 횟수 제한 (매크로 방어). 캡챠는 대기열 입장 한 번만 확인해서, 입장한 뒤에 점유를 반복해서 보내는 것은 막지 못한다.
 *
 * 서버가 여러 대라 횟수를 서버 메모리가 아니라 Redis 에 둔다. 회차를 바꿔 가며 보내는 것도 막으려고 계정 하나로 합쳐 센다.
 * IP 로는 세지 않는다. 운영에서 앱이 보는 IP 가 프론트(Vercel) 서버의 것이라 모든 사용자가 한 사람으로 합쳐진다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(HoldLimitProperties.class)
public class HoldLimitService {

	/** 예매자·판매자·관리자는 번호가 겹칠 수 있어서 계정 종류를 붙인다. 점유는 예매자만 한다 */
	private static final String PREFIX = "tickting:hold-limit:USER:";
	private static final DefaultRedisScript<Long> COUNT_SCRIPT = countScript();

	private final StringRedisTemplate redis;
	private final HoldLimitProperties properties;

	/** 점유 요청을 하나 세고, 정해진 횟수를 넘었으면 BusinessException(BOOKING011) 이다. */
	public void count(Long userId) {
		if (!properties.enabled()) {
			return;
		}
		Long count = redis.execute(COUNT_SCRIPT, List.of(PREFIX + userId),
			String.valueOf(properties.window().toMillis()));
		if (count != null && count > properties.maxRequests()) {
			throw new BusinessException(BookingExceptionCode.HOLD_TOO_MANY_REQUESTS);
		}
	}

	private static DefaultRedisScript<Long> countScript() {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/hold-limit.lua"));
		script.setResultType(Long.class);
		return script;
	}
}
