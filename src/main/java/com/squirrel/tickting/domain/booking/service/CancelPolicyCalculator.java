package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.infra.entity.Booking;
import com.squirrel.tickting.domain.booking.type.BookingStatus;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.SchedulePostponement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * "지금 이 예매를 취소할 수 있는가, 수수료율은 몇 %인가"를 정하는 유일한 곳이다.
 * 예매 취소(BKG-06)와 예매 상세(BKG-05)의 취소 가능 여부·수수료율이 어긋나면 화면에 보인 금액과 실제 환불이 달라져서 한곳에 모았다.
 * 연기 무료 취소 판정도 여기서만 한다. (doc/part3 "연기가 생기면 어떤 테이블이 바뀌나요?")
 *
 * 남은 일수는 달력 날짜 차이로 센다. 공연이 11/30 19시이고 지금이 11/27 23시면 3일 전이다. (시간으로 세면 68시간이라 2일이 된다)
 * 규정 구간이 "공연 3·4·5·6일 전"처럼 날짜로 적혀 있어서 사용자가 읽는 대로 계산되게 했다.
 * 날짜는 서버 시간대 기준이고 서버는 한국 시간으로 돈다. (Dockerfile 의 TZ, -Duser.timezone)
 * 회차가 연기되면 회차의 시작 일시가 바뀐 값이라 바뀐 일시로 센다.
 */
@Component
@RequiredArgsConstructor
public class CancelPolicyCalculator {

	private static final int PERCENT = 100;

	private final ObjectMapper objectMapper;

	/**
	 * 취소 판정 결과.
	 *
	 * @param cancelable       지금 사용자가 취소할 수 있는가
	 * @param feeRate          지금 취소하면 적용되는 수수료율(%). 취소할 수 없으면 null
	 * @param freePostponement 연기 때문에 무료면 그 연기 이력, 아니면 null
	 */
	public record Quote(boolean cancelable, Integer feeRate, SchedulePostponement freePostponement) {

		private static final Quote NOT_CANCELABLE = new Quote(false, null, null);
	}

	/**
	 * 지금 취소하면 어떻게 되는지 판정한다. 공연·회차·연기 이력은 예매의 읽기 전용 연관에서 꺼내므로 트랜잭션 안에서 불러야 한다.
	 *
	 * 순서:
	 *   1. 결제 완료·일부 취소가 아니면 취소할 수 없다.
	 *   2. 연기 무료 취소 대상이면 수수료 0% 로 취소할 수 있다. 새 일정이 미정이면 마감 없이, 원래 일시가 지났어도 된다.
	 *   3. 회차가 이미 시작했으면 취소할 수 없다. (당일을 취소 가능으로 둔 규정이어도 시작 뒤에는 막는다)
	 *   4. 예매 때 복사한 규정에서 남은 일수가 들어가는 구간을 찾는다. 맞는 구간이 없으면 취소할 수 없다.
	 */
	public Quote quote(Booking booking, LocalDateTime now) {
		if (booking.getStatus() != BookingStatus.PAID && booking.getStatus() != BookingStatus.PARTIAL_CANCELED) {
			return Quote.NOT_CANCELABLE;
		}
		PerformanceSchedule schedule = booking.getSchedule();
		SchedulePostponement freePostponement = findFreePostponement(booking, schedule, now);
		if (freePostponement != null) {
			return new Quote(true, 0, freePostponement);
		}
		if (!now.isBefore(schedule.getStartAt())) {
			return Quote.NOT_CANCELABLE;
		}

		long daysBefore = ChronoUnit.DAYS.between(now.toLocalDate(), schedule.getStartAt().toLocalDate());
		return readRules(booking).stream()
			.filter(rule -> rule.daysFrom() <= daysBefore && (rule.daysTo() == null || daysBefore <= rule.daysTo()))
			.findFirst()
			.map(rule -> rule.cancelable() ? new Quote(true, rule.feeRate(), null) : Quote.NOT_CANCELABLE)
			.orElse(Quote.NOT_CANCELABLE);
	}

	/** 좌석 한 장의 취소 수수료. 원 단위 아래는 버린다. */
	public static int seatFee(int price, int feeRate) {
		return price * feeRate / PERCENT;
	}

	/**
	 * 이 예매를 무료로 취소할 수 있게 하는 연기 이력. 없으면 null 이다. 가장 최근 연기 이력만 보고, 무료 취소 기간이고
	 * (일정 미정이면 마감 없음) 예매가 그 연기보다 먼저 생긴 경우만이다. (연기 소식을 알고 예매한 사람은 대상이 아니다)
	 */
	private static SchedulePostponement findFreePostponement(Booking booking, PerformanceSchedule schedule,
		LocalDateTime now) {
		return schedule.findLatestPostponement()
			.filter(latest -> latest.isFreeCancelOpen(now) && latest.isAnnouncedAfter(booking.getCreatedAt()))
			.orElse(null);
	}

	private List<Rule> readRules(Booking booking) {
		return Arrays.asList(objectMapper.readValue(booking.getCancelPolicySnapshot(), Rule[].class));
	}

	/** 예매 때 복사한 취소 규정의 한 구간. 스냅샷 JSON 을 그대로 읽으므로 필드 이름이 스냅샷과 같아야 한다. */
	record Rule(Integer daysFrom, Integer daysTo, Integer feeRate, boolean cancelable) {
	}
}
