package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ScheduleSeatRepository;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.SeatStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScheduleSeatService {

	private final ScheduleSeatRepository scheduleSeatRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;

	/**
	 * 작성 중(DRAFT) 공연의 판매 좌석을 모두 지우고, 취소되지 않은 회차의 좌석 생성 시각을 비워 스케줄러가 다시 만들게 한다.
	 * 공연장을 바꾸면 옛 공연장 좌석의 복사본이 의미가 없어서 쓴다. 작성 중 공연은 예매·점유가 불가능해 HELD·SOLD 가 있을 수 없고,
	 * 판매자 보류석(RESERVED)은 옛 자리의 복사본이라 새 공연장으로 옮길 수 없어 함께 지운다.
	 * 공연 락 안에서 불러야 좌석 생성과 겹치지 않는다. 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public void deleteAllOfPerformance(Long performanceId, LocalDateTime now) {
		scheduleSeatRepository.deleteByPerformanceId(performanceId);
		performanceScheduleRepository.clearSeatsGenerated(performanceId, ScheduleStatus.CANCELED, now);
	}

	/**
	 * 요청한 좌석을 모두 임시 점유(HELD)한다. 하나라도 점유할 수 없으면 예외를 던지고, 호출한 쪽의 트랜잭션이 되돌려서 아무것도 점유하지 않는다.
	 * 분산락 안에서 부르는 것을 전제로 한다. 락이 풀리는 순간에도 중복 점유가 없도록 마지막 판단은 상태 조건이 있는 UPDATE 가 한다.
	 *
	 * @param seatIds 중복이 없는 좌석 번호. 개수가 곧 점유해야 할 행 수다
	 * @return 점유한 좌석. 가격·구역·등급을 예매에 복사하는 용도이고, status·heldBy 는 점유 전 값이다
	 */
	@Transactional
	public List<ScheduleSeat> holdSeats(Long scheduleId, Set<Long> seatIds, Long userId, LocalDateTime now,
		LocalDateTime heldUntil) {
		List<ScheduleSeat> seats = scheduleSeatRepository.findWithLocationByScheduleIdAndIdIn(scheduleId, seatIds);
		if (seats.size() != seatIds.size()) {
			throw new BusinessException(BookingExceptionCode.SEAT_NOT_FOUND);
		}
		if (seats.stream().anyMatch(ScheduleSeatService::isExcludedFromSale)) {
			throw new BusinessException(BookingExceptionCode.SEAT_NOT_SELLABLE);
		}

		int held = scheduleSeatRepository.holdAvailable(scheduleId, seatIds, userId, heldUntil, now);
		if (held != seatIds.size()) {
			throw new BusinessException(BookingExceptionCode.SEAT_ALREADY_TAKEN);
		}
		return seats;
	}

	/**
	 * 점유 중인 좌석을 다시 판매 가능(AVAILABLE)으로 되돌린다. 결제 제한 시간이 지난 예매의 좌석을 풀 때 쓰고,
	 * 그 예매를 만료 처리하는 것과 같은 트랜잭션에서 불러야 한다. (좌석만 풀리고 예매 좌석이 활성으로 남으면 새 점유가 UNIQUE 인덱스와 부딪힌다)
	 *
	 * @return 실제로 되돌린 좌석 수. 그 사이 SOLD 로 바뀐 좌석은 빠지므로 요청 수보다 적을 수 있다
	 */
	@Transactional
	public int releaseHeld(Collection<Long> seatIds, LocalDateTime now) {
		if (seatIds.isEmpty()) {
			return 0;
		}
		return scheduleSeatRepository.releaseHeld(seatIds, now);
	}

	/**
	 * 결제 승인을 시작할 때 좌석의 점유 만료 시각을 늘린다. 예매의 결제 제한 시각을 늘리는 것과 같은 트랜잭션에서 불러야 한다.
	 * 두 값이 어긋나면 한쪽만 먼저 만료되어 스케줄러가 점유 중인 좌석을 풀 수 있다.
	 */
	@Transactional
	public void extendHold(Collection<Long> seatIds, LocalDateTime heldUntil, LocalDateTime now) {
		if (!seatIds.isEmpty()) {
			scheduleSeatRepository.extendHold(seatIds, heldUntil, now);
		}
	}

	/**
	 * 결제가 승인된 좌석을 판매 완료(SOLD)로 바꾼다. 예매를 결제 완료로 바꾸는 것과 같은 트랜잭션에서 불러야 한다.
	 * 요청한 좌석을 모두 바꾸지 못하면(점유가 이미 풀렸거나 다른 상태) 어긋난 상태라서 IllegalStateException 으로 트랜잭션을 되돌린다.
	 */
	@Transactional
	public void markSold(Collection<Long> seatIds, LocalDateTime now) {
		if (seatIds.isEmpty()) {
			return;
		}
		int sold = scheduleSeatRepository.markSold(seatIds, now);
		if (sold != seatIds.size()) {
			throw new IllegalStateException("점유 중인 좌석 " + seatIds.size() + "개 중 " + sold + "개만 판매 완료로 바꿨다.");
		}
	}

	private static boolean isExcludedFromSale(ScheduleSeat seat) {
		return seat.getStatus() == SeatStatus.BLOCKED || seat.getStatus() == SeatStatus.RESERVED;
	}
}
