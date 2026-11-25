package com.squirrel.tickting.domain.booking.facade;

import com.squirrel.tickting.domain.booking.dto.BookingRequest;
import com.squirrel.tickting.domain.booking.dto.BookingResponse;
import com.squirrel.tickting.domain.booking.service.BookingCreateCommand;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import com.squirrel.tickting.domain.venue.infra.entity.VenueSeat;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.domain.performance.service.ScheduleService;
import com.squirrel.tickting.domain.performance.service.ScheduleService.BookableSchedule;
import com.squirrel.tickting.domain.performance.type.ReceiveType;
import com.squirrel.tickting.domain.user.infra.entity.UserMember;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 좌석 점유·예매 생성. 공연(회차·좌석), 공연장(좌석 위치), 회원(주문자), 예매 네 도메인이 얽혀서 서비스끼리 부르지 않고 이 파사드가 순서대로 부른다.
 *
 * 동시성은 Redis 분산락 하나로 막는다. (doc/part4-booking-area.md 4-7)
 * 락을 먼저 잡고 그 안에서 트랜잭션을 열어 커밋한 뒤에 풀어야 해서, 메서드에 @Transactional 을 붙이지 않고 TransactionTemplate 을 쓴다.
 */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(BookingProperties.class)
public class BookingFacade {

	private final DistributedLockExecutor lockExecutor;
	private final TransactionTemplate transactionTemplate;
	private final BookingProperties properties;
	private final ScheduleService scheduleService;
	private final ScheduleSeatService scheduleSeatService;
	private final UserMemberService userMemberService;
	private final BookingService bookingService;

	/**
	 * 좌석을 점유하고 결제 대기 예매를 만든다. 요청한 좌석 중 하나라도 잡지 못하면 아무것도 잡지 않고 전체를 거절한다.
	 *
	 * 락 이름에 공연 번호가 필요해서 회차·공연은 락 밖에서 먼저 읽는다. 읽기만 하고 아무것도 바꾸지 않으며,
	 * 판매 중이 아닌 회차나 한도를 넘는 요청은 락을 잡기 전에 여기서 걸러 락 경쟁을 줄인다.
	 * 이 값은 락 안에서 다시 읽는다. 락을 기다리는 사이에 바뀔 수 있어서 락 밖의 판단은 믿지 않는다.
	 */
	public BookingResponse.Created createBooking(Long userId, Long scheduleId, BookingRequest.Create request) {
		Set<Long> seatIds = new LinkedHashSet<>(request.seatIds());
		if (seatIds.size() != request.seatIds().size()) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT);
		}

		BookableSchedule preview = scheduleService.getBookable(scheduleId, LocalDateTime.now());
		if (seatIds.size() > preview.performance().getMaxPerPerson()) {
			throw new BusinessException(BookingExceptionCode.MAX_PER_PERSON_EXCEEDED);
		}

		// 주문자 이름·연락처는 사람마다 따로 읽는 값이라 경쟁하지 않는다. 락 안에서 읽으면 그만큼 락을 오래 쥐므로 밖에서 읽는다.
		UserMember user = userMemberService.getMe(userId);

		List<String> lockNames = new ArrayList<>();
		seatIds.forEach(seatId -> lockNames.add("seat:" + seatId));
		lockNames.add("user-performance:" + userId + ":" + preview.performanceId());

		return lockExecutor.execute(lockNames, properties.lockWait(),
			() -> transactionTemplate.execute(status -> hold(userId, scheduleId, seatIds, preview.cancelRules(), user)));
	}

	/**
	 * 락을 잡은 상태에서 한 트랜잭션으로 실행한다. 하나라도 실패하면 점유와 예매 생성이 모두 되돌려진다.
	 *
	 * 락을 쥔 시간이 곧 같은 좌석을 두고 기다리는 요청의 대기 시간이라서 락 안의 DB 왕복을 줄이는 순서로 짰다. (doc/load-test-plan 19장)
	 *   - 점유 UPDATE 를 가장 먼저 한다. 인기 좌석에서는 대부분의 요청이 이미 점유된 좌석에 부딪혀 실패하는데, 판매 가능 여부와
	 *     누적 매수를 확인한 뒤에 UPDATE 하면 실패에 이르기까지 왕복이 몇 번 더 든다. 아래 확인에서 걸리면 예외로 트랜잭션이 되돌려져
	 *     점유도 함께 사라지므로 순서를 바꿔도 결과는 같다. 단, 둘 다 어긋난 드문 경우의 오류 코드는 점유 쪽이 먼저 나온다.
	 *   - 회차는 판매 중인지만 다시 확인한다. 취소 규정은 락 밖에서 읽은 값을 쓴다.
	 */
	private BookingResponse.Created hold(Long userId, Long scheduleId, Set<Long> seatIds,
		List<CancelPolicyRule> cancelRules, UserMember user) {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime expiresAt = now.plus(properties.holdDuration());
		List<ScheduleSeat> held = scheduleSeatService.holdSeats(scheduleId, seatIds, userId, now, expiresAt);

		Performance performance = scheduleService.checkBookable(scheduleId, now);
		long owned = bookingService.countActiveSeats(userId, performance.getId());
		if (owned + seatIds.size() > performance.getMaxPerPerson()) {
			throw new BusinessException(BookingExceptionCode.MAX_PER_PERSON_EXCEEDED);
		}

		// 응답의 좌석 순서는 요청한 순서를 따른다. (좌석은 최대 몇 장이라 목록에서 순서를 찾아도 부담이 없다)
		List<Long> requestedOrder = List.copyOf(seatIds);
		List<BookingCreateCommand.SeatLine> lines = held.stream()
			.sorted(Comparator.comparingInt(seat -> requestedOrder.indexOf(seat.getId())))
			.map(BookingFacade::toLine)
			.toList();

		return bookingService.create(new BookingCreateCommand(
			userId,
			scheduleId,
			performance.getId(),
			performance.getBusinessId(),
			defaultReceiveMethod(performance.getReceiveType()),
			user.getName(),
			user.getPhone(),
			user.getEmail(),
			performance.getBookingFee() == null ? 0 : performance.getBookingFee(),
			lines,
			cancelRules.stream()
				.map(rule -> new BookingCreateCommand.CancelRule(
					rule.getDaysFrom(), rule.getDaysTo(), rule.getFeeRate(), rule.isCancelable()))
				.toList(),
			now.toLocalDate(),
			expiresAt));
	}

	/**
	 * 예매에는 점유한 좌석의 위치와 등급을 문자열로 복사해 둔다. (공연장·등급이 나중에 바뀌어도 예매 당시 그대로 보이게)
	 * 구역·열·번호는 좌석의 공연장 좌석 연관에서, 등급은 좌석의 등급 연관에서 꺼낸다. 등급은 코드를 그대로 저장하고 화면 이름은 프론트가 정한다.
	 */
	private static BookingCreateCommand.SeatLine toLine(ScheduleSeat seat) {
		VenueSeat venueSeat = seat.getVenueSeat();
		return new BookingCreateCommand.SeatLine(seat.getId(), venueSeat.getZone().getName(), venueSeat.getRowLabel(),
			venueSeat.getSeatNumber(), seat.getGrade().getName().name(), seat.getPrice());
	}

	/** 점유할 때는 수령 방법을 정하지 않아서 기본값을 넣고, 결제 준비 때 확정한다. 배송만 되는 공연이 아니면 현장 수령이다. */
	private static ReceiveMethod defaultReceiveMethod(ReceiveType allowed) {
		return allowed == ReceiveType.DELIVERY ? ReceiveMethod.DELIVERY : ReceiveMethod.ONSITE;
	}
}
