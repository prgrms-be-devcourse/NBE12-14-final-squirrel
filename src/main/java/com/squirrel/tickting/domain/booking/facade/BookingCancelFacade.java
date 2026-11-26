package com.squirrel.tickting.domain.booking.facade;

import com.squirrel.tickting.domain.booking.dto.BookingRequest;
import com.squirrel.tickting.domain.booking.dto.BookingResponse;
import com.squirrel.tickting.domain.booking.service.BookingCancelPlan;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.payment.gateway.GatewayRejectedException;
import com.squirrel.tickting.domain.payment.gateway.GatewayUnavailableException;
import com.squirrel.tickting.domain.payment.gateway.PaymentGateway;
import com.squirrel.tickting.domain.payment.infra.entity.Payment;
import com.squirrel.tickting.domain.payment.infra.entity.Refund;
import com.squirrel.tickting.domain.payment.service.PaymentService;
import com.squirrel.tickting.domain.payment.service.RefundService;
import com.squirrel.tickting.domain.payment.type.RefundReasonType;
import com.squirrel.tickting.domain.payment.type.RefundStatus;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예매 취소(전체·일부)와 환불. 예매(좌석 취소·상태), 결제(결제 상태·환불), 공연(판매 좌석 되돌리기) 세 도메인에 쓰기가 걸려서
 * 서비스끼리 부르지 않고 이 파사드가 엮는다. (doc/part4-booking-area.md "③ 일부 좌석 취소")
 *
 * 세 단계로 나뉘고, 토스를 부르는 두 번째 단계만 DB 트랜잭션과 락 밖이다.
 *   1. 취소 (예매 락 + 트랜잭션): 수수료를 계산하고, 환불 행(PROCESSING)을 만들고, 좌석을 취소하고, 판매 좌석을 되돌리고,
 *      예매·결제 상태를 바꾼다. 여기서 커밋하면 좌석 취소는 끝난 것이다.
 *   2. 환불 (트랜잭션 밖): 토스에 환불을 요청한다. 응답이 느려도 DB 연결과 락을 붙잡지 않는다.
 *   3. 반영 (트랜잭션): 환불을 완료(DONE) 또는 실패(FAILED)로 기록한다.
 *
 * 환불 행을 토스 요청 전에 PROCESSING 으로 커밋해 두는 이유: 토스를 부르는 도중 서버가 죽어도 보내다 만 환불이 DB 에 남아서,
 * 재시도가 같은 멱등키로 이어서 처리할 수 있다. 같은 멱등키의 재요청은 토스가 두 번 돌려주지 않는다.
 *
 * 같은 예매의 취소는 예매 단위 분산락(booking:{id})으로 하나씩 처리한다. 결제 승인과 같은 락이라 승인과도 겹치지 않는다.
 * 락이 뚫려도 좌석 취소·예매 상태 UPDATE 의 상태 조건이 마지막으로 막아 같은 좌석이 두 번 환불되지 않는다.
 *
 * 판매자가 회차를 취소한 예매는 예매자가 아니라 스케줄러가 이 파사드로 처리한다. 순서가 반대라 환불에 성공한 뒤에 예매를 취소한다.
 * (화면의 "결제 완료·취소"가 돈의 상태와 맞게) 토스에 보내는 일은 환불 스케줄러(RefundRetryFacade)가 맡고 여기서는 부르지 않는다.
 *   ① 환불 만들기(requestScheduleRefunds): 취소 회차의 결제 완료·일부 취소 예매마다 수수료 없는 환불(REQUESTED)을 만든다.
 *   ② 예매에 반영(applyScheduleCancels): 그 환불이 완료(DONE)되면 남은 좌석을 취소하고 판매 좌석을 되돌린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingCancelFacade {

	private final DistributedLockExecutor lockExecutor;
	private final TransactionTemplate transactionTemplate;
	private final BookingProperties properties;
	private final BookingService bookingService;
	private final PaymentService paymentService;
	private final RefundService refundService;
	private final ScheduleSeatService scheduleSeatService;
	private final PaymentGateway paymentGateway;

	/** @param request 본문 없이 오면 null 이고, 전체 취소로 본다 */
	public BookingResponse.Canceled cancel(Long userId, Long bookingId, BookingRequest.Cancel request) {
		List<Long> bookingSeatIds = request == null ? null : request.bookingSeatIds();
		Canceled canceled = lockExecutor.execute(List.of(bookingLock(bookingId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> cancelInTransaction(userId, bookingId, bookingSeatIds)));

		RefundStatus refundStatus = refund(canceled);

		BookingCancelPlan plan = canceled.plan();
		return new BookingResponse.Canceled(
			bookingId,
			plan.fullyCanceled() ? BookingStatus.CANCELED : BookingStatus.PARTIAL_CANCELED,
			plan.bookingSeatIds(),
			plan.cancelFeeTotal(),
			new BookingResponse.Refund(canceled.refundId(), plan.refundAmount(), refundStatus));
	}

	/**
	 * 취소 회차의 예매에 환불(REQUESTED)을 만든다. 예매·결제·좌석 상태는 바꾸지 않는다.
	 *
	 * @return 이번에 환불을 만든 예매 수
	 */
	public int requestScheduleRefunds(LocalDateTime now) {
		return forEachBooking(bookingService::findScheduleRefundTargets,
			bookingId -> requestScheduleRefund(bookingId, now), "회차 취소 환불을 만들지 못했다");
	}

	/**
	 * 완료된 회차 취소 환불을 예매에 반영한다.
	 *
	 * @return 이번에 취소로 바꾼 예매 수
	 */
	public int applyScheduleCancels(LocalDateTime now) {
		return forEachBooking(bookingService::findScheduleCancelApplyTargets,
			bookingId -> applyScheduleCancel(bookingId, now), "끝난 회차 취소 환불을 예매에 반영하지 못했다");
	}

	/**
	 * 대상 예매를 번호 순으로 batchSize 건씩 가져와 모두 처리한다. 예매 하나가 락 하나·트랜잭션 하나라 하나가 실패해도 나머지는 이어 가고,
	 * 실패한 예매는 대상에 남아 다음 실행에서 다시 시도한다.
	 * 예매 락은 기다리지 않는다. 취소 회차는 결제·직접 취소가 막혀 겹치는 일이 드물고, 못 잡으면 다음 실행에서 처리하면 된다.
	 */
	private int forEachBooking(BiFunction<Long, Integer, List<Long>> findTargets, Predicate<Long> process,
		String failMessage) {
		int batchSize = properties.scheduleCancel().batchSize();
		int processed = 0;
		long afterId = 0;
		List<Long> bookingIds;
		do {
			bookingIds = findTargets.apply(afterId, batchSize);
			for (Long bookingId : bookingIds) {
				afterId = bookingId;
				try {
					if (lockExecutor.execute(List.of(bookingLock(bookingId)), Duration.ZERO,
						() -> transactionTemplate.execute(status -> process.test(bookingId)))) {
						processed++;
					}
				} catch (BusinessException e) {
					if (e.getExceptionCode() != CommonExceptionCode.LOCK_ACQUISITION_FAILED) {
						log.error("{}. 예매 번호: {}", failMessage, bookingId, e);
					}
				} catch (RuntimeException e) {
					log.error("{}. 예매 번호: {}", failMessage, bookingId, e);
				}
			}
		} while (bookingIds.size() == batchSize);
		return processed;
	}

	private boolean requestScheduleRefund(Long bookingId, LocalDateTime now) {
		Optional<BookingService.ScheduleRefund> target = bookingService.prepareScheduleRefund(bookingId, now);
		target.ifPresent(refund -> refundService.request(paymentService.getRefundable(bookingId), bookingId,
			refund.amount(), RefundReasonType.PERFORMANCE_CANCEL, refund.reason(), now));
		return target.isPresent();
	}

	/**
	 * 결제 상태 변경을 좌석 취소(일괄 UPDATE)보다 먼저 하는 이유는 cancelInTransaction 과 같다.
	 * 실패하면 환불 번호를 붙여 다시 던진다. 돈은 돌려줬는데 예매가 결제 완료로 남은 건이고 관리자 환불 목록에는 완료로만 보여서,
	 * 로그의 예매 번호·환불 번호로 찾아야 한다.
	 */
	private boolean applyScheduleCancel(Long bookingId, LocalDateTime now) {
		Optional<BookingService.ScheduleCancel> target = bookingService.planScheduleCancel(bookingId);
		target.ifPresent(cancel -> {
			try {
				paymentService.applyCancel(paymentService.getRefundable(bookingId), true);
				List<Long> scheduleSeatIds = bookingService.applyScheduleCancel(cancel.plan(), cancel.refundId(), now);
				scheduleSeatService.releaseSold(scheduleSeatIds, now);
			} catch (RuntimeException e) {
				throw new IllegalStateException("회차 취소 반영에 실패했다. 환불 번호: " + cancel.refundId(), e);
			}
		});
		return target.isPresent();
	}

	/** 1단계에서 정한 값. 토스에는 이 값만 보낸다. */
	private record Canceled(BookingCancelPlan plan, Long refundId, String paymentKey, String idempotencyKey) {
	}

	/**
	 * 결제 상태 변경과 환불 행 저장을 좌석 취소보다 먼저 한다. 좌석 취소·예매 상태는 일괄 UPDATE 라서 영속성 컨텍스트를 비우는데,
	 * 그 뒤에 엔티티를 고치면 저장되지 않기 때문이다. (일괄 UPDATE 가 비우기 전에 먼저 flush 한다)
	 */
	private Canceled cancelInTransaction(Long userId, Long bookingId, List<Long> bookingSeatIds) {
		LocalDateTime now = LocalDateTime.now();
		BookingCancelPlan plan = bookingService.planCancel(userId, bookingId, bookingSeatIds, now);

		Payment payment = paymentService.getRefundable(bookingId);
		Refund refund = refundService.start(payment, bookingId, plan.refundAmount(), RefundReasonType.USER_CANCEL, now);
		paymentService.applyCancel(payment, plan.fullyCanceled());

		List<Long> scheduleSeatIds = bookingService.applyCancel(plan, refund.getId(), now);
		scheduleSeatService.releaseSold(scheduleSeatIds, now);
		return new Canceled(plan, refund.getId(), payment.getPaymentKey(), refund.getIdempotencyKey());
	}

	/**
	 * 토스에 환불을 요청하고 결과를 기록한다. 거절이든 결과를 모르는 경우든 실패(FAILED)로 남기고 예외를 던지지 않는다.
	 * 좌석 취소는 이미 커밋돼서 실패로 답하면 사용자가 실제 상태와 다른 결과를 보게 된다. 실패한 환불은 같은 멱등키로 다시 보낸다.
	 * 돌려줄 금액이 0원이면(수수료 100% 규정) 토스를 부르지 않고 바로 완료로 기록한다. (토스는 0원 취소를 받지 않는다)
	 */
	private RefundStatus refund(Canceled canceled) {
		BookingCancelPlan plan = canceled.plan();
		String failReason = null;
		if (plan.refundAmount() > 0) {
			try {
				paymentGateway.cancel(canceled.paymentKey(), plan.refundAmount(),
					RefundReasonType.USER_CANCEL.getGatewayReason(), canceled.idempotencyKey());
			} catch (GatewayRejectedException e) {
				failReason = e.getCode() + ": " + e.getMessage();
			} catch (GatewayUnavailableException e) {
				failReason = "환불 결과를 알 수 없음: " + e.getMessage();
			}
		}
		if (failReason != null) {
			log.warn("토스 환불에 실패해 재시도 대상으로 남긴다. 환불 번호: {}", canceled.refundId());
		}

		String reason = failReason;
		try {
			Refund refund = transactionTemplate.execute(status -> reason == null
				? refundService.complete(canceled.refundId(), LocalDateTime.now())
				: refundService.fail(canceled.refundId(), reason));
			return refund.getStatus();
		} catch (RuntimeException e) {
			// 결과 기록에 실패했다. 환불은 요청 중(PROCESSING)으로 남고, 재시도가 같은 멱등키로 확정한다.
			log.error("토스 환불 결과를 기록하지 못했다. 환불 번호: {}", canceled.refundId(), e);
			return RefundStatus.PROCESSING;
		}
	}

	private static String bookingLock(Long bookingId) {
		return "booking:" + bookingId;
	}
}
