package com.squirrel.tickting.domain.queue.service;

import com.squirrel.tickting.domain.queue.dto.QueueResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * 회차별 대기열. 상태는 모두 Redis 에 있어서 서버가 몇 대든 어느 서버로 요청이 가도 같은 결과가 나온다.
 * DB 는 쓰지 않는다. (대기열은 DB 를 지키려고 앞에 두는 것이라, 대기열 자체가 DB 를 건드리면 의미가 없다)
 *
 * <p>Redis 구조 (회차 번호를 중괄호로 묶어 같은 회차의 키가 같은 슬롯에 놓이게 했다. 클러스터로 옮겨도 스크립트가 돈다)
 * <ul>
 *   <li>{@code tickting:queue:{회차}:wait} 대기 ZSET. 멤버 USER:번호, 점수 도착 순번</li>
 *   <li>{@code tickting:queue:{회차}:active} 입장 ZSET. 멤버 USER:번호, 점수 만료 시각(ms)</li>
 *   <li>{@code tickting:queue:{회차}:seq} 도착 순번 카운터</li>
 *   <li>{@code tickting:queue:{회차}:seen:USER:번호} 대기 중인 사람이 최근에 상태를 확인했다는 표시(짧은 만료)</li>
 *   <li>{@code tickting:queue:{회차}:open} 이 회차가 입장을 받을 수 있다고 DB 로 확인한 표시(짧은 만료)</li>
 *   <li>{@code tickting:queue:{회차}:status} DB 에서 읽은 회차 상태. 일시 중지·취소를 알아보는 데 쓴다(짧은 만료)</li>
 * </ul>
 * 멤버에는 계정 종류(USER)를 붙인다. 예매자·판매자·관리자는 번호가 겹칠 수 있다.
 *
 * <p>입장 처리는 별도 스케줄러 없이 상태 확인(폴링)할 때 같이 한다. 줄 서 있는 사람은 어차피 몇 초마다 확인하고,
 * 확인하는 순간 빈 자리만큼 앞사람부터 입장시키므로 스케줄러가 없어도 순서와 정원이 지켜진다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(QueueProperties.class)
public class QueueService {

	private static final String PREFIX = "tickting:queue:";
	private static final String MEMBER_PREFIX = "USER:";
	/** 끝난 회차의 키가 남지 않게 두는 유효 시간. 쓸 때마다 늘어난다 */
	private static final Duration KEY_TTL = Duration.ofDays(1);
	/** 회차가 지금 입장을 받을 수 있는지 DB 로 확인한 결과를 기억하는 시간. 줄 서는 요청마다 DB 를 읽지 않으려는 것이다 */
	private static final Duration OPEN_CHECK_TTL = Duration.ofSeconds(10);

	private static final DefaultRedisScript<List> POLL_SCRIPT = script("redis/queue-poll.lua", List.class);
	private static final DefaultRedisScript<Long> CHECK_SCRIPT = script("redis/queue-check.lua", Long.class);

	private final StringRedisTemplate redis;
	private final QueueProperties properties;
	private final MeterRegistry meterRegistry;

	/** 줄에 선다. 자리가 있으면 바로 입장하고, 이미 줄에 있거나 입장해 있으면 그대로 현재 상태를 준다. */
	public QueueResponse enter(Long userId, Long scheduleId) {
		return poll(userId, scheduleId, true, false);
	}

	/**
	 * 내 상태를 본다. 이때 빈 자리가 있으면 앞사람부터 입장시킨다. 줄에 없으면 NONE 이다.
	 * halted 면 아무도 새로 입장시키지 않고 순번만 유지하며, 응답의 paused 가 true 다.
	 */
	public QueueResponse status(Long userId, Long scheduleId, boolean halted) {
		return poll(userId, scheduleId, false, halted);
	}

	/**
	 * 새로 줄을 세우지 않고, 이미 대기·입장 중이면 그 상태를 준다. 아무도 새로 입장시키지 않는다.
	 * 대기열을 끈 환경에는 줄 자체가 없으므로 NONE 이다.
	 */
	public QueueResponse findJoined(Long userId, Long scheduleId) {
		return properties.enabled() ? poll(userId, scheduleId, false, true) : QueueResponse.none();
	}

	/** 줄에서 나가거나 입장 자리를 반납한다. 없는 사람이어도 그대로 성공이다. */
	public void leave(Long userId, Long scheduleId) {
		if (!properties.enabled()) {
			return;
		}
		String member = member(userId);
		redis.opsForZSet().remove(waitKey(scheduleId), member);
		redis.opsForZSet().remove(activeKey(scheduleId), member);
		redis.delete(seenKey(scheduleId, member));
		redis.delete(admittedKey(scheduleId, member));
	}

	/**
	 * 결제를 마친 사람의 입장 자리를 바로 돌려준다. 한 번 사면 끝이라서 1인 한도가 남아 있어도 더 사려면 다시 줄을 서야 한다.
	 * 나가기(DELETE)와 같은 동작이고, 어디서 반납됐는지 지표로 가르려고 이름만 따로 둔다.
	 */
	public void releaseOnPayment(Long userId, Long scheduleId) {
		leave(userId, scheduleId);
		meterRegistry.counter("queue.release", "reason", "payment").increment();
	}

	/** 지금 입장해 있는지(만료 전인지). 좌석 조회·점유 요청마다 부른다. */
	public boolean isAdmitted(Long userId, Long scheduleId) {
		if (!properties.enabled()) {
			return true;
		}
		String member = member(userId);
		Long admitted = redis.execute(CHECK_SCRIPT, List.of(activeKey(scheduleId), admittedKey(scheduleId, member)), member,
			String.valueOf(properties.entryTtl().toMillis()));
		return admitted != null && admitted == 1L;
	}

	/** 이 회차가 입장을 받을 수 있다고 최근에 DB 로 확인했는지. */
	public boolean isKnownOpen(Long scheduleId) {
		return Boolean.TRUE.equals(redis.hasKey(openKey(scheduleId)));
	}

	public void markOpen(Long scheduleId) {
		redis.opsForValue().set(openKey(scheduleId), "1", OPEN_CHECK_TTL);
	}

	/** 누가 한 번이라도 줄을 선 회차인지. 아니면 지킬 순번이 없어서 회차 상태를 읽거나 기억할 일이 없다. */
	public boolean hasQueue(Long scheduleId) {
		return Boolean.TRUE.equals(redis.hasKey(seqKey(scheduleId)));
	}

	/** 최근에 DB 에서 읽어 둔 회차 상태. 없으면 비어 있다. */
	public Optional<String> findKnownScheduleStatus(Long scheduleId) {
		return Optional.ofNullable(redis.opsForValue().get(statusKey(scheduleId)));
	}

	public void rememberScheduleStatus(Long scheduleId, String scheduleStatus) {
		redis.opsForValue().set(statusKey(scheduleId), scheduleStatus, OPEN_CHECK_TTL);
	}

	private QueueResponse poll(Long userId, Long scheduleId, boolean join, boolean halted) {
		if (!properties.enabled()) {
			return QueueResponse.admitted(LocalDateTime.now().plus(properties.entryTtl()), halted);
		}
		String member = member(userId);
		List<?> result = redis.execute(POLL_SCRIPT,
			List.of(waitKey(scheduleId), activeKey(scheduleId), seqKey(scheduleId), seenKey(scheduleId, member)),
			member, join ? "1" : "0",
			String.valueOf(properties.maxActive()),
			String.valueOf(properties.entryTtl().toMillis()),
			String.valueOf(properties.waitingTimeout().toMillis()),
			String.valueOf(KEY_TTL.toMillis()),
			seenPrefix(scheduleId), halted ? "1" : "0",
			admittedPrefix(scheduleId), String.valueOf(properties.entryMax().toMillis()));
		if (result == null || result.size() < 3) {
			throw new IllegalStateException("대기열 스크립트가 값을 돌려주지 않았다.");
		}
		long state = ((Number) result.get(0)).longValue();
		long first = ((Number) result.get(1)).longValue();
		long second = ((Number) result.get(2)).longValue();
		if (state == 1) {
			return QueueResponse.admitted(LocalDateTime.ofInstant(Instant.ofEpochMilli(first), ZoneId.systemDefault()),
				halted);
		}
		if (state == 2) {
			return QueueResponse.waiting((int) first, second, halted);
		}
		return QueueResponse.none();
	}

	private static String member(Long userId) {
		return MEMBER_PREFIX + userId;
	}

	private static String base(Long scheduleId) {
		return PREFIX + "{" + scheduleId + "}:";
	}

	private static String waitKey(Long scheduleId) {
		return base(scheduleId) + "wait";
	}

	private static String activeKey(Long scheduleId) {
		return base(scheduleId) + "active";
	}

	private static String seqKey(Long scheduleId) {
		return base(scheduleId) + "seq";
	}

	private static String seenPrefix(Long scheduleId) {
		return base(scheduleId) + "seen:";
	}

	private static String seenKey(Long scheduleId, String member) {
		return seenPrefix(scheduleId) + member;
	}

	private static String admittedPrefix(Long scheduleId) {
		return base(scheduleId) + "admitted:";
	}

	private static String admittedKey(Long scheduleId, String member) {
		return admittedPrefix(scheduleId) + member;
	}

	private static String openKey(Long scheduleId) {
		return base(scheduleId) + "open";
	}

	private static String statusKey(Long scheduleId) {
		return base(scheduleId) + "status";
	}

	private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(path));
		script.setResultType(resultType);
		return script;
	}
}
