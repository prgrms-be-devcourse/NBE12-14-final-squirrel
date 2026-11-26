package com.squirrel.tickting.domain.queue.service;

import com.squirrel.tickting.domain.queue.dto.QueueResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
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
 *   <li>{@code tickting:queue:{회차}:keep:USER:번호} 좌석 화면을 떠난 입장자의 원래 만료 시각(유예 동안만)</li>
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
	private static final DefaultRedisScript<Long> LEAVE_SOON_SCRIPT = script("redis/queue-leave-soon.lua", Long.class);

	private final StringRedisTemplate redis;
	private final QueueProperties properties;

	/** 줄에 선다. 자리가 있으면 바로 입장하고, 이미 줄에 있거나 입장해 있으면 그대로 현재 상태를 준다. */
	public QueueResponse enter(Long userId, Long scheduleId) {
		return poll(userId, scheduleId, true);
	}

	/** 내 상태를 본다. 이때 빈 자리가 있으면 앞사람부터 입장시킨다. 줄에 없으면 NONE 이다. */
	public QueueResponse status(Long userId, Long scheduleId) {
		return poll(userId, scheduleId, false);
	}

	/** 줄에서 나가거나 입장 자리를 반납한다. 없는 사람이어도 그대로 성공이다. */
	public void leave(Long userId, Long scheduleId) {
		if (!properties.enabled()) {
			return;
		}
		String member = member(userId);
		redis.opsForZSet().remove(waitKey(scheduleId), member);
		redis.opsForZSet().remove(activeKey(scheduleId), member);
		redis.delete(List.of(seenKey(scheduleId, member), keepKey(scheduleId, member)));
	}

	/**
	 * 입장한 사람이 좌석 화면을 떠났다. 바로 비우지 않고 leaveGrace 뒤에 비워지게 한다. 화면은 새로고침·창 닫기·뒤로 가기를 믿을 만하게
	 * 구분할 수 없어서, 바로 비우면 새로고침만 해도 줄을 처음부터 다시 서게 된다. 그 전에 돌아오면(입장·상태 확인·좌석 조회·점유)
	 * 원래 입장 만료 시각이 되살아난다. 입장해 있지 않으면 아무것도 하지 않는다. (대기 중인 사람의 순번은 건드리지 않는다)
	 */
	public void leaveSoon(Long userId, Long scheduleId) {
		if (!properties.enabled()) {
			return;
		}
		String member = member(userId);
		redis.execute(LEAVE_SOON_SCRIPT, List.of(activeKey(scheduleId), keepKey(scheduleId, member)),
			member, String.valueOf(properties.leaveGrace().toMillis()));
	}

	/** 지금 입장해 있는지(만료 전인지). 좌석 조회·점유 요청마다 부른다. */
	public boolean isAdmitted(Long userId, Long scheduleId) {
		if (!properties.enabled()) {
			return true;
		}
		String member = member(userId);
		Long admitted = redis.execute(CHECK_SCRIPT,
			List.of(activeKey(scheduleId), keepKey(scheduleId, member)), member);
		return admitted != null && admitted == 1L;
	}

	/** 이 회차가 입장을 받을 수 있다고 최근에 DB 로 확인했는지. */
	public boolean isKnownOpen(Long scheduleId) {
		return Boolean.TRUE.equals(redis.hasKey(openKey(scheduleId)));
	}

	public void markOpen(Long scheduleId) {
		redis.opsForValue().set(openKey(scheduleId), "1", OPEN_CHECK_TTL);
	}

	private QueueResponse poll(Long userId, Long scheduleId, boolean join) {
		if (!properties.enabled()) {
			return QueueResponse.admitted(LocalDateTime.now().plus(properties.entryTtl()));
		}
		String member = member(userId);
		List<?> result = redis.execute(POLL_SCRIPT,
			List.of(waitKey(scheduleId), activeKey(scheduleId), seqKey(scheduleId), seenKey(scheduleId, member),
				keepKey(scheduleId, member)),
			member, join ? "1" : "0",
			String.valueOf(properties.maxActive()),
			String.valueOf(properties.entryTtl().toMillis()),
			String.valueOf(properties.waitingTimeout().toMillis()),
			String.valueOf(KEY_TTL.toMillis()),
			seenPrefix(scheduleId));
		if (result == null || result.size() < 3) {
			throw new IllegalStateException("대기열 스크립트가 값을 돌려주지 않았다.");
		}
		long state = ((Number) result.get(0)).longValue();
		long first = ((Number) result.get(1)).longValue();
		long second = ((Number) result.get(2)).longValue();
		if (state == 1) {
			return QueueResponse.admitted(LocalDateTime.ofInstant(Instant.ofEpochMilli(first), ZoneId.systemDefault()));
		}
		if (state == 2) {
			return QueueResponse.waiting((int) first, second);
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

	private static String openKey(Long scheduleId) {
		return base(scheduleId) + "open";
	}

	private static String keepKey(Long scheduleId, String member) {
		return base(scheduleId) + "keep:" + member;
	}

	private static <T> DefaultRedisScript<T> script(String path, Class<T> resultType) {
		DefaultRedisScript<T> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(path));
		script.setResultType(resultType);
		return script;
	}
}
