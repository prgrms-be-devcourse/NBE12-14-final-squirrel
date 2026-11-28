package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ScheduleSeatRepository;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.SeatStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
	 * 고른 좌석의 판매자 보류를 지정(AVAILABLE → RESERVED)하거나 해제(RESERVED → AVAILABLE)한다. 공연 락 안의 트랜잭션에서 부른다.
	 * 바꿀 수 없는 좌석은 건너뛰고 지금 상태를 알려 준다.
	 * 바뀐 좌석은 "보류였는지"가 뒤집힌 좌석이다. RESERVED 를 만들거나 없애는 것은 공연 락을 잡는 판매자 흐름뿐이라 뒤집혔으면 이 요청이 바꾼 것이다.
	 * AVAILABLE 로는 판단할 수 없다. 해제 중에 점유 만료·예매 취소가 다른 좌석을 AVAILABLE 로 되돌릴 수 있다.
	 *
	 * @param seatIds 중복이 없는 좌석 번호
	 */
	@Transactional
	public SeatReservationChange changeReservation(Long scheduleId, Set<Long> seatIds, boolean reserve,
		LocalDateTime now) {
		List<ScheduleSeat> before = scheduleSeatRepository.findWithLocationByScheduleIdAndIdIn(scheduleId, seatIds);
		if (before.size() != seatIds.size()) {
			throw new BusinessException(PerformanceExceptionCode.SELLER_SEAT_NOT_FOUND);
		}
		Set<Long> reservedBefore = before.stream()
			.filter(seat -> seat.getStatus() == SeatStatus.RESERVED)
			.map(ScheduleSeat::getId)
			.collect(Collectors.toSet());
		scheduleSeatRepository.changeStatus(scheduleId, seatIds, reservationFrom(reserve), reservationTo(reserve), now);

		List<ScheduleSeat> changed = new ArrayList<>();
		List<ScheduleSeat> skipped = new ArrayList<>();
		scheduleSeatRepository.findWithLocationByScheduleIdAndIdIn(scheduleId, seatIds).stream()
			.sorted(Comparator.comparing(ScheduleSeat::getId))
			.forEach(seat -> {
				boolean flipped = reservedBefore.contains(seat.getId()) != (seat.getStatus() == SeatStatus.RESERVED);
				(flipped ? changed : skipped).add(seat);
			});
		if (changed.isEmpty()) {
			throw noSeatToChange(reserve);
		}
		return new SeatReservationChange(changed, skipped);
	}

	/**
	 * 구역을 통째로 지정·해제한다. 구역마다 UPDATE 한 문장이라 돌려받은 행 수가 곧 바뀐 수다.
	 *
	 * @param zoneIds 그 회차 공연장의 구역인지 확인한, 중복 없는 번호
	 * @return zoneIds 와 같은 순서의 구역별 바뀐 좌석 수
	 */
	@Transactional
	public List<Integer> changeZoneReservation(Long scheduleId, List<Long> zoneIds, boolean reserve, LocalDateTime now) {
		List<Integer> changedSeats = zoneIds.stream()
			.map(zoneId -> scheduleSeatRepository.changeZoneStatus(scheduleId, zoneId, reservationFrom(reserve),
				reservationTo(reserve), now))
			.toList();
		if (changedSeats.stream().allMatch(count -> count == 0)) {
			throw noSeatToChange(reserve);
		}
		return changedSeats;
	}

	private static SeatStatus reservationFrom(boolean reserve) {
		return reserve ? SeatStatus.AVAILABLE : SeatStatus.RESERVED;
	}

	private static SeatStatus reservationTo(boolean reserve) {
		return reserve ? SeatStatus.RESERVED : SeatStatus.AVAILABLE;
	}

	private static BusinessException noSeatToChange(boolean reserve) {
		return new BusinessException(reserve ? PerformanceExceptionCode.NO_SEAT_TO_RESERVE
			: PerformanceExceptionCode.NO_SEAT_TO_RELEASE);
	}

	private static boolean isExcludedFromSale(ScheduleSeat seat) {
		return seat.getStatus() == SeatStatus.BLOCKED || seat.getStatus() == SeatStatus.RESERVED;
	}

	/** 보류 지정·해제 결과. 두 목록 모두 좌석 번호순이고, 좌석은 바꾼 뒤 다시 읽은 값이다. */
	public record SeatReservationChange(List<ScheduleSeat> changed, List<ScheduleSeat> skipped) {
	}
}
