package com.squirrel.tickting.domain.booking.facade;

import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.payment.facade.PaymentReconcileProperties;
import com.squirrel.tickting.domain.payment.service.PaymentService;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제하지 않고 떠난 좌석 정리. 예매(booking)와 판매 좌석(schedule_seat)은 서로 다른 도메인이라 파사드가 같은 트랜잭션으로 묶는다.
 *
 * 예매를 만료 처리하는 것과 좌석을 되돌리는 것은 반드시 함께 일어나야 한다.
 * 좌석만 먼저 풀리면 낡은 예매 좌석이 활성으로 남아 있어 그 좌석을 새로 점유한 요청이 UNIQUE 인덱스(uq_booking_seat_active)와 부딪힌다.
 * 그래서 좌석의 held_until 이 아니라 만료된 예매를 기준으로 그 예매의 좌석만 되돌린다.
 *
 * 승인 중(PROCESSING) 결제가 있는 예매는 제한 시각이 지나도 만료하지 않고 보정 작업이 결제 결과를 확인할 때까지 둔다.
 * 승인 응답을 잃은 결제는 승인 시작 뒤 수 분이 지나야 보정 작업이 토스에 조회하는데, 점유는 그보다 먼저 끝난다(승인 시작 뒤 2분 연장).
 * 그 사이 만료하면 토스에서 결제됐어도 좌석이 이미 풀려서 사용자는 돈을 돌려받고 좌석을 잃는다.
 * 보정 작업이 결제됨으로 확인하면 예매를 확정하고, 결제 안 됨으로 확인하면 예매를 만료시켜 좌석을 풀어 준다.
 * 다만 보정 작업이 포기하는 시간(giveUpAfter)이 지나도 승인 중이면(조회가 계속 실패하는 경우) 기다리지 않고 만료한다.
 * 그래야 조회가 고쳐지지 않아도 좌석이 영원히 묶이지 않는다. 그 뒤에 결제가 확인되면 보정 작업이 자동 환불한다.
 *
 * 기다릴 예매 목록은 배치를 돌기 전에 한 번 읽는다. 그 뒤에 승인을 시작한 결제의 예매는 지금(now) 기준으로 제한 시각이 지나지 않았다.
 * (승인 시작이 제한 시각 전인지 확인하고, 점유를 늘린다) 그래서 목록을 읽은 뒤 승인이 시작돼도 그 예매를 만료하는 일은 없다.
 *
 * 분산락(Redis)은 쓰지 않는다. 여러 서버가 같은 예매를 두 번 처리하는 일은 DB 의 SKIP LOCKED 가 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryFacade {

	private final TransactionTemplate transactionTemplate;
	private final BookingService bookingService;
	private final ScheduleSeatService scheduleSeatService;
	private final PaymentService paymentService;
	private final PaymentReconcileProperties reconcileProperties;

	/**
	 * 만료된 예매가 더 없을 때까지 batchSize 건씩 나눠 처리한다. 한 번에 몰아서 하면 트랜잭션이 길어져 잠금이 오래 이어진다.
	 * 배치마다 트랜잭션을 따로 열어서, 중간에 실패해도 앞 배치의 처리는 남는다.
	 *
	 * @return 되돌린 좌석 수
	 */
	public int expireOverdue(LocalDateTime now, int batchSize) {
		List<Long> awaitingReconcile = paymentService.findProcessingBookingIds(now.minus(reconcileProperties.giveUpAfter()));
		int released = 0;
		while (true) {
			List<Long> processed = transactionTemplate.execute(status -> {
				List<Long> scheduleSeatIds = bookingService.expireOverdue(now, batchSize, awaitingReconcile);
				int count = scheduleSeatService.releaseHeld(scheduleSeatIds, now);
				if (count != scheduleSeatIds.size()) {
					// 예매는 결제 대기인데 좌석이 점유 상태가 아니다. 두 테이블이 어긋난 것이라 원인을 찾아야 한다.
					log.warn("만료된 예매의 좌석 {}개 중 {}개만 점유 상태였다.", scheduleSeatIds.size(), count);
				}
				return scheduleSeatIds;
			});
			if (processed == null || processed.isEmpty()) {
				return released;
			}
			released += processed.size();
		}
	}
}
