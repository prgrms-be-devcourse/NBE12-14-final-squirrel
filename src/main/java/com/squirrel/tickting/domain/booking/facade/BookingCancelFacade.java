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
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.LocalDateTime;
import java.util.List;
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
