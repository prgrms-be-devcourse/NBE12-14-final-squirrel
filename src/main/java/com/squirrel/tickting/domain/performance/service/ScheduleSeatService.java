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
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleSeatService {

	private final ScheduleSeatRepository scheduleSeatRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;
	private final PerformanceProperties properties;
	private final TransactionTemplate transactionTemplate;

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
	 * 점유 UPDATE 를 먼저 하고 좌석은 그 뒤에 읽는다. 인기 좌석에 요청이 몰리면 대부분은 이미 점유된 좌석이라 실패하는데, 실패하는
	 * 요청도 락을 차례로 잡고 들어오기 때문에 실패까지 걸리는 왕복 수가 곧 뒤따르는 요청의 대기 시간이다. 읽기를 앞에 두면 실패에
	 * 이르기까지 왕복이 더 든다. 좌석을 읽는 것은 성공하면 예매에 복사할 값을 얻으려는 것이고, 실패하면 오류의 이유
	 * (없음·판매 제외·이미 점유됨)를 가르려는 것이다.
	 *
	 * @param seatIds 중복이 없는 좌석 번호. 개수가 곧 점유해야 할 행 수다
	 * @return 점유한 좌석. 가격·구역·등급을 예매에 복사하는 용도다. UPDATE 뒤에 읽으므로 status·heldBy 는 점유한 값이다
	 */
	@Transactional
	public List<ScheduleSeat> holdSeats(Long scheduleId, Set<Long> seatIds, Long userId, LocalDateTime now,
		LocalDateTime heldUntil) {
		int held = scheduleSeatRepository.holdAvailable(scheduleId, seatIds, userId, heldUntil, now);
		List<ScheduleSeat> seats = scheduleSeatRepository.findWithLocationByScheduleIdAndIdIn(scheduleId, seatIds);
		if (held == seatIds.size()) {
			return seats;
		}

		// 일부 좌석을 점유했을 수 있어도 예외로 호출한 쪽의 트랜잭션이 되돌려서 아무것도 점유하지 않는다.
		if (seats.size() != seatIds.size()) {
			throw new BusinessException(BookingExceptionCode.SEAT_NOT_FOUND);
		}
		if (seats.stream().anyMatch(ScheduleSeatService::isExcludedFromSale)) {
			throw new BusinessException(BookingExceptionCode.SEAT_NOT_SELLABLE);
		}
		throw new BusinessException(BookingExceptionCode.SEAT_ALREADY_TAKEN);
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
	 * 취소된 예매의 판매 완료 좌석을 다시 판매 가능(AVAILABLE)으로 되돌린다. 예매 좌석을 취소(active=false)하는 것과
	 * 같은 트랜잭션에서 불러야 한다. (좌석만 풀리고 예매 좌석이 활성으로 남으면 새 점유가 UNIQUE 인덱스와 부딪힌다)
	 *
	 * @return 실제로 되돌린 좌석 수. SOLD 가 아닌 좌석은 빠지므로 요청 수보다 적을 수 있다
	 */
	@Transactional
	public int releaseSold(Collection<Long> seatIds, LocalDateTime now) {
		if (seatIds.isEmpty()) {
			return 0;
		}
		return scheduleSeatRepository.releaseSold(seatIds, now);
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

	/**
	 * 공연 일시부터 보관 기간(app.performance.seat-cleanup.retention)이 지난 회차의 판매 좌석을 지운다. (doc/part3-performance-area.md 3-5)
	 * 지우기 직전의 상태별 좌석 수를 회차에 남겨서 판매 현황(SLR-17)이 같은 값을 계속 보여 준다.
	 * 회차 하나가 트랜잭션 하나다. 한 회차가 실패해도 나머지는 계속하고, 실패한 회차는 다음 실행에서 다시 대상이 된다.
	 *
	 * 환불이 남은 회차도 지운다. 환불을 보내고 다시 보내는 흐름은 refund·payment 만 쓰고, 좌석을 돌려주는 UPDATE 는 대상이 없으면
	 * 0행으로 끝나서 좌석 행이 없어도 실패하지 않는다. 기다리게 하면 재시도를 다 쓴 실패 환불이 있는 회차가 영원히 남는다.
	 * 락은 잡지 않는다. 끝난 회차의 좌석은 바꾸는 흐름이 없고, 서버끼리 겹치면 markSeatsPurged 의 조건이 한쪽만 통과시킨다.
	 *
	 * @return 좌석을 지운 회차 수
	 */
	public int purgeEndedSeats(LocalDateTime now) {
		LocalDateTime cutoff = now.minus(properties.seatCleanup().retention());
		int purged = 0;
		for (Long scheduleId : performanceScheduleRepository.findSeatPurgeTargets(cutoff)) {
			try {
				Optional<Integer> deleted = transactionTemplate.execute(status -> purgeSeatsOf(scheduleId, now));
				if (deleted != null && deleted.isPresent()) {
					purged++;
					log.info("끝난 회차의 판매 좌석을 지웠다. 회차 {}, 좌석 {}개", scheduleId, deleted.get());
				} else {
					log.debug("다른 서버가 먼저 정리한 회차라 건너뛴다. 회차 {}", scheduleId);
				}
			} catch (RuntimeException e) {
				log.error("끝난 회차의 판매 좌석을 지우지 못했다. 다음 실행에서 다시 시도한다. 회차 {}", scheduleId, e);
			}
		}
		return purged;
	}

	/**
	 * 한 회차의 좌석을 세서 회차에 남기고 지운다. 다른 서버가 먼저 정리했으면 비어 있다.
	 * 세기·표시·삭제가 한 트랜잭션이라, 센 뒤 지우기 전에 값이 바뀌는 틈이 없다. (끝난 회차라 바꾸는 흐름도 없다)
	 */
	private Optional<Integer> purgeSeatsOf(Long scheduleId, LocalDateTime now) {
		List<ScheduleSeatRepository.SeatStatusCount> counts = scheduleSeatRepository.countByStatus(List.of(scheduleId));
		int marked = performanceScheduleRepository.markSeatsPurged(scheduleId, count(counts, SeatStatus.SOLD),
			count(counts, SeatStatus.HELD), count(counts, SeatStatus.AVAILABLE), count(counts, SeatStatus.RESERVED),
			count(counts, SeatStatus.BLOCKED), now);
		if (marked == 0) {
			return Optional.empty();
		}
		return Optional.of(scheduleSeatRepository.deleteByScheduleId(scheduleId));
	}

	private static int count(List<ScheduleSeatRepository.SeatStatusCount> counts, SeatStatus status) {
		return Math.toIntExact(counts.stream().filter(c -> c.getStatus() == status)
			.mapToLong(ScheduleSeatRepository.SeatStatusCount::getCount).sum());
	}

	private static boolean isExcludedFromSale(ScheduleSeat seat) {
		return seat.getStatus() == SeatStatus.BLOCKED || seat.getStatus() == SeatStatus.RESERVED;
	}
}
