package com.squirrel.tickting.domain.booking.facade;

import com.squirrel.tickting.domain.booking.service.BookingService;
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
 * 분산락(Redis)은 쓰지 않는다. 여러 서버가 같은 예매를 두 번 처리하는 일은 DB 의 SKIP LOCKED 가 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryFacade {

	private final TransactionTemplate transactionTemplate;
	private final BookingService bookingService;
	private final ScheduleSeatService scheduleSeatService;

	/**
	 * 만료된 예매가 더 없을 때까지 batchSize 건씩 나눠 처리한다. 한 번에 몰아서 하면 트랜잭션이 길어져 잠금이 오래 이어진다.
	 * 배치마다 트랜잭션을 따로 열어서, 중간에 실패해도 앞 배치의 처리는 남는다.
	 *
	 * @return 되돌린 좌석 수
	 */
	public int expireOverdue(LocalDateTime now, int batchSize) {
		int released = 0;
		while (true) {
			List<Long> processed = transactionTemplate.execute(status -> {
				List<Long> scheduleSeatIds = bookingService.expireOverdue(now, batchSize);
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
