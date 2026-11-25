package com.squirrel.tickting.domain.payment.facade;

import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.domain.audit.type.ActorType;
import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.payment.gateway.GatewayLookup;
import com.squirrel.tickting.domain.payment.gateway.GatewayPayment;
import com.squirrel.tickting.domain.payment.gateway.GatewayUnavailableException;
import com.squirrel.tickting.domain.payment.gateway.PaymentGateway;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.service.PaymentService;
import com.squirrel.tickting.domain.payment.service.RefundService;
import com.squirrel.tickting.domain.payment.type.PaymentStatus;
import com.squirrel.tickting.domain.payment.type.RefundReasonType;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 불일치 보정. 승인 요청의 응답을 받지 못했거나 응답을 받고도 우리 쪽 반영이 실패해서 승인 중(PROCESSING)으로 남은 결제와,
 * 준비(READY)로 오래 남은 결제를 토스에 조회해서 확정한다. (doc/part5-operations-area.md 5-2 ②)
 * 결제(payment), 예매(booking), 판매 좌석(schedule_seat), 환불(refund), 감사 로그가 얽혀서 서비스끼리 부르지 않고 이 파사드가 엮는다.
 *
 * 토스에 조회한 결과로 나눈다. 돈이 나갔는지가 기준이고, 모르면 아무것도 바꾸지 않는다.
 *   1. 결제됨 + 예매가 아직 결제 대기(좌석이 아직 내 것): 예매를 확정한다. 결제 DONE, 예매 PAID, 좌석 SOLD.
 *   2. 결제됨 + 예매가 이미 만료됐거나 다른 결제로 끝남: 받은 돈을 돌려준다. 결제 DONE 으로 기록한 뒤 환불(사유 SYSTEM)을 만들어
 *      토스에 보내고 결제를 CANCELED 로 바꾼다. 만료된 예매는 CANCELED 로 바꾼다. 감사 로그(SYSTEM)를 남긴다.
 *      토스에 보내는 일이 실패하면 환불 재시도 작업이 같은 멱등키로 이어서 처리한다.
 *   3. 결제 안 됨(조회되는 결제가 없거나 실패·만료·취소로 끝남): 결제를 FAILED 로 닫고 예매를 바로 만료시켜 좌석을 풀어 준다.
 *      같은 예매에 다른 결제가 진행 중이거나 승인돼 있으면 예매는 건드리지 않는다.
 *   4. 아직 모름(토스가 끝나지 않았다고 답함, 조회 실패): 아무것도 바꾸지 않고 다음 주기에 다시 조회한다.
 *      단 토스가 끝나지 않았다고만 답하며 giveUpAfter 가 지난 결제는 3번으로 본다. (더 이상 결제될 수 없다)
 *
 * 서버가 여러 대여도 안전하다. 결제를 바꾸는 쪽은 예매 단위 분산락(booking:{id}) 안에서 결제가 아직 닫히지 않았는지 다시 확인하고,
 * 다른 서버나 승인 요청이 먼저 끝냈다면 아무것도 하지 않는다. 같은 락을 결제 승인·예매 취소도 쓴다.
 * 토스를 부르는 조회와 환불은 DB 트랜잭션과 락 밖에서 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(PaymentReconcileProperties.class)
public class PaymentReconcileFacade {

	private static final String AUDIT_TARGET_TYPE = "PAYMENT";
	private static final String AUDIT_ACTION_AUTO_REFUND = "AUTO_REFUND";
	private static final String METRIC_RESULT = "payment.reconcile";
	private static final String METRIC_LOOKUP = "payment.reconcile.lookup";
	private static final String METRIC_CYCLE = "payment.reconcile.cycle";
	private static final String METRIC_LAST_DUE = "payment.reconcile.last.due";

	private final DistributedLockExecutor lockExecutor;
	private final TransactionTemplate transactionTemplate;
	private final PaymentProperties paymentProperties;
	private final PaymentReconcileProperties properties;
	private final PaymentGateway paymentGateway;
	private final PaymentService paymentService;
	private final RefundService refundService;
	private final RefundSender refundSender;
	private final BookingService bookingService;
	private final ScheduleSeatService scheduleSeatService;
	private final AuditLogService auditLogService;
	private final MeterRegistry meterRegistry;

	/** 마지막 주기에 조회 대상으로 골라낸 결제 수. 한 번에 batchSize 까지만 고르므로, 이 값이 계속 batchSize 이면 처리가 유입을 따라가지 못하는 것이다. */
	private final AtomicInteger lastDue = new AtomicInteger();

	@PostConstruct
	void registerMetrics() {
		meterRegistry.gauge(METRIC_LAST_DUE, lastDue);
	}

	/** 결제 보정 결과를 센다. result: confirmed 확정, refunded 자동 환불, closed 결제 안 됨으로 닫음, undecided 모름, lookup_failed 조회 실패,
	 *  unchanged 다른 서버·승인이 먼저 끝냄, needs_review 사람이 확인해야 함, skipped 락 때문에 건너뜀, error 예외 */
	private void count(String result) {
		meterRegistry.counter(METRIC_RESULT, "result", result).increment();
	}

	/** 락 안에서 결제를 바꾼 결과. 환불을 만들었으면 트랜잭션이 끝난 뒤 토스에 보낼 환불 번호가 들어 있다. */
	private record Settled(boolean changed, Long refundId) {

		static Settled unchanged() {
			return new Settled(false, null);
		}
	}

	/**
	 * 확인할 때가 된 결제를 batchSize 건까지 하나씩 차례로 조회해서 확정한다. 한 건이 예외로 끝나도 나머지는 이어서 처리한다.
	 *
	 * @return 이번에 상태를 바꾼 결제 수
	 */
	public int reconcileDue(LocalDateTime now) {
		Timer.Sample cycle = Timer.start(meterRegistry);
		try {
			List<Long> paymentIds = paymentService.findReconcileDue(now.minus(properties.readyAfter()),
				now.minus(properties.processingAfter()), properties.batchSize());
			lastDue.set(paymentIds.size());
			int reconciled = 0;
			for (Long paymentId : paymentIds) {
				try {
					if (reconcile(paymentId, now)) {
						reconciled++;
					}
				} catch (BusinessException e) {
					// 같은 예매를 결제·취소하는 중이라 락을 못 잡은 것처럼 흔히 있는 일이다. 다음 주기에 다시 한다.
					count("skipped");
					log.info("결제 보정을 건너뛴다. 다음 주기에 다시 시도한다. 결제 번호: {}, 사유: {}", paymentId, e.getMessage());
				} catch (RuntimeException e) {
					// 트랜잭션은 되돌려져서 결제는 그대로이고, 토스에 이미 보낸 환불은 환불 재시도 작업이 이어서 처리한다.
					count("error");
					log.error("결제 보정에 실패했다. 다음 주기에 다시 시도한다. 결제 번호: {}", paymentId, e);
				}
			}
			return reconciled;
		} finally {
			cycle.stop(meterRegistry.timer(METRIC_CYCLE));
		}
	}

	private boolean reconcile(Long paymentId, LocalDateTime now) {
		Payment payment = paymentService.find(paymentId).orElse(null);
		if (payment == null || !isUnsettled(payment)) {
			return false;
		}

		GatewayLookup lookup;
		Timer.Sample lookupTime = Timer.start(meterRegistry);
		try {
			lookup = paymentGateway.lookup(payment.getOrderId());
		} catch (GatewayUnavailableException e) {
			count("lookup_failed");
			log.warn("토스 결제 조회 결과를 알 수 없어 결제를 그대로 둔다. 결제 번호: {}", paymentId);
			return false;
		} finally {
			lookupTime.stop(meterRegistry.timer(METRIC_LOOKUP));
		}

		GatewayLookup.Outcome outcome = lookup.outcome();
		if (outcome == GatewayLookup.Outcome.UNDECIDED) {
			if (age(payment, now).compareTo(properties.giveUpAfter()) < 0) {
				count("undecided");
				return false;
			}
			log.info("토스에서 끝나지 않은 채 오래된 결제를 결제되지 않은 것으로 닫는다. 결제 번호: {}", paymentId);
			outcome = GatewayLookup.Outcome.NOT_PAID;
		}
		return outcome == GatewayLookup.Outcome.PAID ? settlePaid(payment, lookup, now) : settleUnpaid(payment, now);
	}

	// ---------------------------------------------------------------- 결제 안 됨

	private boolean settleUnpaid(Payment snapshot, LocalDateTime now) {
		Boolean closed = withBookingLock(snapshot.getBookingId(), () -> transactionTemplate.execute(status -> {
			Optional<Payment> current = paymentService.findUnsettled(snapshot.getId());
			if (current.isEmpty()) {
				// 조회하는 사이 승인 요청이나 다른 서버가 먼저 끝냈다.
				return false;
			}
			Payment payment = current.get();
			// 결제 엔티티를 먼저 바꾼다. 예매·좌석의 일괄 UPDATE 는 영속성 컨텍스트를 비워서, 그 뒤에 엔티티를 바꾸면 저장되지 않는다.
			paymentService.abandon(payment, "토스 조회 결과 결제되지 않음");
			if (!paymentService.existsOtherLivePayment(payment.getBookingId(), payment.getId())) {
				List<Long> scheduleSeatIds = bookingService.expire(payment.getBookingId(), now);
				scheduleSeatService.releaseHeld(scheduleSeatIds, now);
			}
			return true;
		}));
		if (Boolean.TRUE.equals(closed)) {
			count("closed");
			log.info("결제되지 않은 결제를 닫았다. 결제 번호: {}, 예매 번호: {}", snapshot.getId(), snapshot.getBookingId());
		} else {
			count("unchanged");
		}
		return Boolean.TRUE.equals(closed);
	}

	// ---------------------------------------------------------------- 결제됨

	private boolean settlePaid(Payment snapshot, GatewayLookup lookup, LocalDateTime now) {
		if (snapshot.getStatus() == PaymentStatus.READY) {
			// 승인 요청은 반드시 결제를 승인 중으로 바꾼 뒤 토스를 부르므로 일어날 수 없는 일이다. 돈이 걸려서 임의로 처리하지 않는다.
			log.error("승인을 시작하지 않은(READY) 결제가 토스에서 결제 완료로 조회됐다. 자동으로 처리하지 않으니 사람이 확인해야 한다. "
				+ "결제 번호: {}, 주문 ID: {}", snapshot.getId(), snapshot.getOrderId());
			count("needs_review");
			return false;
		}
		if (!snapshot.getAmount().equals(lookup.amount())) {
			count("needs_review");
			log.error("토스에서 결제된 금액이 우리가 계산한 금액과 다르다. 자동으로 처리하지 않으니 사람이 확인해야 한다. "
				+ "결제 번호: {}, 주문 ID: {}, 우리 금액: {}, 토스 금액: {}",
				snapshot.getId(), snapshot.getOrderId(), snapshot.getAmount(), lookup.amount());
			return false;
		}

		Settled settled = withBookingLock(snapshot.getBookingId(),
			() -> transactionTemplate.execute(status -> settlePaidInTransaction(snapshot.getId(), lookup.payment(), now)));
		if (settled == null || !settled.changed()) {
			count("unchanged");
			return false;
		}
		if (settled.refundId() != null) {
			count("refunded");
			sendRefund(settled.refundId());
		} else {
			count("confirmed");
		}
		return true;
	}

	private Settled settlePaidInTransaction(Long paymentId, GatewayPayment approved, LocalDateTime now) {
		Optional<Payment> current = paymentService.findUnsettled(paymentId);
		if (current.isEmpty() || current.get().getStatus() != PaymentStatus.PROCESSING) {
			// 조회하는 사이 승인 요청이나 다른 서버가 먼저 끝냈다.
			return Settled.unchanged();
		}
		Payment payment = current.get();
		Booking booking = bookingService.getById(payment.getBookingId());
		BookingStatus bookingStatus = booking.getStatus();

		if (bookingStatus == BookingStatus.PENDING) {
			// 좌석이 아직 내 것이다. 승인 응답을 잃었을 뿐이니 정상 승인과 같이 예매를 확정한다.
			// 그 사이 만료 정리가 먼저 예매를 만료시켰다면 markPaid 가 실패해 트랜잭션이 되돌려지고, 다음 주기에 환불로 처리한다.
			paymentService.complete(paymentId, approved.paymentKey(), approved.method(), approved.approvedAt());
			bookingService.markPaid(booking.getId(), now);
			scheduleSeatService.markSold(bookingService.findActiveScheduleSeatIds(booking.getId()), now);
			log.info("승인 결과를 잃었던 결제를 토스 조회로 확정했다. 결제 번호: {}, 예매 번호: {}", paymentId, booking.getId());
			return new Settled(true, null);
		}

		// 예매가 이미 만료됐거나 다른 결제로 끝나서 좌석을 줄 수 없다. 받은 돈을 그대로 돌려준다.
		paymentService.complete(paymentId, approved.paymentKey(), approved.method(), approved.approvedAt());
		Refund refund = refundService.start(payment, booking.getId(), payment.getAmount(), RefundReasonType.SYSTEM, now);
		paymentService.applyCancel(payment, true);
		auditLogService.saveHistory(AuditLogCommand.builder()
			.actorType(ActorType.SYSTEM)
			.targetType(AUDIT_TARGET_TYPE)
			.targetId(paymentId)
			.action(AUDIT_ACTION_AUTO_REFUND)
			.before(Map.of("paymentStatus", PaymentStatus.PROCESSING, "bookingStatus", bookingStatus))
			.after(Map.of("paymentStatus", PaymentStatus.CANCELED, "refundId", refund.getId(), "amount", refund.getAmount()))
			.build());
		// 마지막에 한다. 일괄 UPDATE 가 영속성 컨텍스트를 비워서, 앞의 엔티티 변경은 그 전에 저장(flush)된다.
		bookingService.cancelExpired(booking.getId(), now);
		log.warn("토스에서 결제됐지만 좌석을 줄 수 없어 자동으로 환불한다. 결제 번호: {}, 예매 번호: {}, 예매 상태: {}",
			paymentId, booking.getId(), bookingStatus);
		return new Settled(true, refund.getId());
	}

	/**
	 * 만든 환불을 토스에 보내고 결과를 기록한다. DB 트랜잭션과 락 밖에서 한다. 실패하거나 도중에 서버가 죽어도 환불은 요청 중(PROCESSING)으로
	 * 남아서 환불 재시도 작업이 같은 멱등키로 이어서 처리한다.
	 */
	private void sendRefund(Long refundId) {
		Refund refund = refundService.getWithPayment(refundId);
		String failReason = refundSender.send(refund);
		transactionTemplate.executeWithoutResult(status -> {
			if (failReason == null) {
				refundService.complete(refundId, LocalDateTime.now());
			} else {
				refundService.fail(refundId, failReason);
			}
		});
		if (failReason != null) {
			log.warn("자동 환불을 토스에 보냈지만 실패했다. 환불 재시도 작업이 이어서 처리한다. 환불 번호: {}", refundId);
		}
	}

	// ---------------------------------------------------------------- 공통

	private static boolean isUnsettled(Payment payment) {
		return payment.getStatus() == PaymentStatus.READY || payment.getStatus() == PaymentStatus.PROCESSING;
	}

	/** 결제가 이 상태로 남은 지 얼마나 됐나. 승인 중은 승인을 시작한 때부터, 준비는 준비한 때부터 잰다. */
	private static Duration age(Payment payment, LocalDateTime now) {
		LocalDateTime since = payment.getProcessingStartedAt() != null ? payment.getProcessingStartedAt() : payment.getCreatedAt();
		return Duration.between(since, now);
	}

	/** 결제 승인·예매 취소와 같은 예매 단위 락을 잡고 그 안에서 실행한다. 락을 못 잡으면 COMMON004 BusinessException 이다. */
	private <T> T withBookingLock(Long bookingId, Supplier<T> action) {
		return lockExecutor.execute(List.of("booking:" + bookingId), paymentProperties.lockWait(), action);
	}
}
