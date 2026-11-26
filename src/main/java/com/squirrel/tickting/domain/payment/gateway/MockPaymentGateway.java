package com.squirrel.tickting.domain.payment.gateway;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 결제 승인·환불 Mock. 토스 키 없이 결제 흐름을 만들고 테스트하려고 둔다. 실제 대행사를 부르지 않고 돈도 움직이지 않는다.
 *
 * 결제 키로 결과를 고른다. (결제 키 접두사)
 *   reject_              승인 거절 (카드사 거절)
 *   unavailable_         승인 여부를 알 수 없음 (통신 오류). 토스에서는 승인되지 않은 것으로 조회된다
 *   lostpaid_            승인 여부를 알 수 없음. 하지만 토스에서는 승인된 것으로 조회된다 (응답만 잃은 경우. 결제 보정 시험용)
 *   slow_                승인은 성공하지만 느리게 응답 (동시 요청이 겹치는 상황을 만들 때)
 *   refund_reject_       승인은 성공, 환불은 거절
 *   refund_unavailable_  승인은 성공, 환불 결과를 알 수 없음
 *   그 밖의 값            성공
 *
 * 결제 조회(lookup)는 결제 키를 받지만 주문 ID 로 답한다. (승인 요청에 온 키와 주문의 짝은 토스 구현과 그 테스트가 확인한다)
 * 기본으로 "승인에 성공한 주문(lostpaid_ 포함)은 결제됨, 그 밖은 결제 안 됨"이다. 기본과 다른 결과가 필요하면
 * 테스트가 reportLookup 으로 주문별 결과를 정하고, 조회 자체가 실패하는 상황은 failLookups 로 만든다.
 *
 * 부하 테스트에서 토스의 응답 시간을 흉내 내려고 승인·조회에 지연을 줄 수 있다. (app.payment.mock.confirm-delay, lookup-delay. 기본 0)
 *
 * 누가 승인을 요청했는지와 상관없이 무조건 승인하므로 운영에서는 절대 쓰면 안 된다. (prod 프로필에서는 등록되지 않는다)
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "app.payment", name = "gateway", havingValue = "mock")
public class MockPaymentGateway implements PaymentGateway {

	private static final long SLOW_MILLIS = 300;
	private static final String SHARED_PAID_PREFIX = "mock:toss:paid:";

	/** 서버 여러 대가 같은 "토스"를 보게 하는 공유 기록용. 단위 테스트처럼 Redis 가 없으면 비어 있다. */
	@Autowired(required = false)
	private StringRedisTemplate redis;

	private final Queue<String> confirmedOrderIds = new ConcurrentLinkedQueue<>();
	/** 승인에 성공한 주문의 결제. 조회의 기본 결과(결제됨)를 만드는 데 쓴다. */
	private final Map<String, GatewayLookup> succeeded = new ConcurrentHashMap<>();
	/** 테스트가 정한 주문별 조회 결과. 기본 결과보다 우선한다. */
	private final Map<String, GatewayLookup> reported = new ConcurrentHashMap<>();
	private final Queue<String> lookedUpOrderIds = new ConcurrentLinkedQueue<>();
	private volatile boolean lookupsFail;

	/** 승인·조회에 거는 지연. 부하 테스트가 토스의 응답 시간을 흉내 낼 때만 쓰고, 기본은 0 이다. */
	@Value("${app.payment.mock.confirm-delay:0ms}")
	private Duration confirmDelay = Duration.ZERO;

	@Value("${app.payment.mock.lookup-delay:0ms}")
	private Duration lookupDelay = Duration.ZERO;
	private final Queue<CancelCall> cancelCalls = new ConcurrentLinkedQueue<>();

	/** 환불 요청 한 번. 테스트에서 금액과 멱등키를 확인하는 데 쓴다. */
	public record CancelCall(String paymentKey, int amount, String idempotencyKey) {
	}

	@Override
	public GatewayPayment confirm(String paymentKey, String orderId, int amount) {
		confirmedOrderIds.add(orderId);
		sleep(confirmDelay);
		if (paymentKey.startsWith("lostpaid_")) {
			// 토스에서는 승인됐는데 응답을 받지 못했다. 조회하면 결제됨으로 나온다.
			succeeded.put(orderId, GatewayLookup.paid(new GatewayPayment(paymentKey, "카드", LocalDateTime.now()), amount));
			shareLostPaid(orderId, paymentKey, amount);
			throw new GatewayUnavailableException("Mock: 승인은 됐지만 응답을 받지 못함");
		}
		if (paymentKey.startsWith("reject_")) {
			throw new GatewayRejectedException("REJECT_CARD_COMPANY", "카드사에서 결제를 거절했습니다.");
		}
		if (paymentKey.startsWith("unavailable_")) {
			throw new GatewayUnavailableException("Mock: 결제 대행사 응답 없음");
		}
		if (paymentKey.startsWith("slow_")) {
			sleep(Duration.ofMillis(SLOW_MILLIS));
		}
		GatewayPayment payment = new GatewayPayment(paymentKey, "카드", LocalDateTime.now());
		succeeded.put(orderId, GatewayLookup.paid(payment, amount));
		return payment;
	}

	@Override
	public GatewayLookup lookup(String paymentKey, String orderId) {
		lookedUpOrderIds.add(orderId);
		sleep(lookupDelay);
		if (lookupsFail) {
			throw new GatewayUnavailableException("Mock: 결제 조회 실패");
		}
		GatewayLookup result = reported.get(orderId);
		if (result != null) {
			return result;
		}
		GatewayLookup local = succeeded.get(orderId);
		if (local != null) {
			return local;
		}
		GatewayLookup shared = sharedLostPaid(orderId);
		return shared != null ? shared : GatewayLookup.notPaid();
	}

	/**
	 * 승인 응답을 잃은 결제는 Redis 에도 남긴다. 실제 토스는 어느 서버가 물어봐도 같은 답을 주는데, 서버 메모리에만 두면
	 * 서버가 여럿일 때 승인 요청을 받은 서버가 아닌 쪽의 조회가 "결제 안 됨"으로 틀리게 답해서 서버 여러 대 시험이 어긋난다.
	 * Redis 가 없으면(단위 테스트의 일부) 메모리 기록만 쓴다.
	 */
	private void shareLostPaid(String orderId, String paymentKey, int amount) {
		if (redis != null) {
			redis.opsForValue().set(SHARED_PAID_PREFIX + orderId, paymentKey + "|" + amount, Duration.ofHours(1));
		}
	}

	private GatewayLookup sharedLostPaid(String orderId) {
		if (redis == null) {
			return null;
		}
		String value = redis.opsForValue().get(SHARED_PAID_PREFIX + orderId);
		if (value == null) {
			return null;
		}
		int bar = value.lastIndexOf('|');
		return GatewayLookup.paid(new GatewayPayment(value.substring(0, bar), "카드", LocalDateTime.now()),
			Integer.parseInt(value.substring(bar + 1)));
	}

	/** 테스트가 한 주문의 조회 결과를 정한다. 승인 응답을 잃었지만 토스에서는 결제된 경우 등을 만들 때 쓴다. */
	public void reportLookup(String orderId, GatewayLookup result) {
		reported.put(orderId, result);
	}

	/** 테스트가 결제 조회를 모두 실패(결과를 알 수 없음)시키거나 되돌린다. */
	public void failLookups(boolean fail) {
		this.lookupsFail = fail;
	}

	/** 테스트에서 어떤 주문을 몇 번 조회했는지 확인하는 데 쓴다. */
	public List<String> lookedUpOrderIds() {
		return List.copyOf(lookedUpOrderIds);
	}

	@Override
	public void cancel(String paymentKey, int amount, String reason, String idempotencyKey) {
		cancelCalls.add(new CancelCall(paymentKey, amount, idempotencyKey));
		if (paymentKey.startsWith("refund_reject_")) {
			throw new GatewayRejectedException("NOT_CANCELABLE_PAYMENT", "취소할 수 없는 결제입니다.");
		}
		if (paymentKey.startsWith("refund_unavailable_")) {
			throw new GatewayUnavailableException("Mock: 결제 대행사 응답 없음");
		}
	}

	/** 테스트에서 승인 요청이 몇 번, 어떤 주문으로 들어왔는지 확인하는 데 쓴다. */
	public List<String> confirmedOrderIds() {
		return List.copyOf(confirmedOrderIds);
	}

	/** 테스트에서 환불 요청이 몇 번, 얼마로 들어왔는지 확인하는 데 쓴다. */
	public List<CancelCall> cancelCalls() {
		return List.copyOf(cancelCalls);
	}

	public void reset() {
		confirmedOrderIds.clear();
		cancelCalls.clear();
		succeeded.clear();
		reported.clear();
		lookedUpOrderIds.clear();
		lookupsFail = false;
	}

	private static void sleep(Duration delay) {
		if (delay == null || delay.isZero() || delay.isNegative()) {
			return;
		}
		try {
			Thread.sleep(delay.toMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
