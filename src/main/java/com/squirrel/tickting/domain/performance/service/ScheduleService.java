package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.SchedulePostponementRepository;
import com.squirrel.tickting.domain.performance.infra.repository.SeatGradeRepository;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ScheduleService {

	private final PerformanceScheduleRepository performanceScheduleRepository;
	private final SchedulePostponementRepository schedulePostponementRepository;
	private final SeatGradeRepository seatGradeRepository;
	private final CancelPolicyRuleRepository cancelPolicyRuleRepository;
	private final PerformanceRepository performanceRepository;

	/**
	 * 결제 준비에 필요한 공연(제목, 허용하는 수령 방법)을 읽는다. 공개 상태가 아니면(작성 중·취소됨) 결제할 수 없다.
	 * 이미 만들어진 예매의 결제라서 판매 기간은 따지지 않는다. 점유한 뒤 판매 종료 시각이 지나도 결제는 마칠 수 있어야 한다.
	 * 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional(readOnly = true)
	public Performance getPayablePerformance(Long performanceId) {
		return performanceRepository.findById(performanceId)
			.filter(performance -> performance.getStatus() == PerformanceStatus.PUBLISHED)
			.orElseThrow(() -> new BusinessException(BookingExceptionCode.BOOKING_NOT_PAYABLE));
	}

	/**
	 * 지금 예매할 수 있는 회차를 읽는다. 읽기만 하고 아무것도 바꾸지 않는다.
	 * 없는 회차와 공개되지 않은(작성 중·취소된) 공연의 회차는 같은 404 다. (번호가 존재하는지 알려주지 않으려고)
	 * 공개된 공연이라도 판매 기간 밖이거나 회차가 중지·연기·취소됐거나 이미 시작했으면 판매 중이 아니다.
	 *
	 * 등급 이름과 취소 규정은 지연 로딩이라 트랜잭션이 끝난 뒤에는 꺼낼 수 없어서 여기서 모두 읽어 담는다.
	 */
	@Transactional(readOnly = true)
	public BookableSchedule getBookable(Long scheduleId, LocalDateTime now) {
		PerformanceSchedule schedule = performanceScheduleRepository.findById(scheduleId)
			.orElseThrow(() -> new BusinessException(BookingExceptionCode.SCHEDULE_NOT_FOUND));
		Performance performance = schedule.getPerformance();
		if (performance.getStatus() != PerformanceStatus.PUBLISHED) {
			throw new BusinessException(BookingExceptionCode.SCHEDULE_NOT_FOUND);
		}

		boolean saleOpen = !now.isBefore(performance.getSaleOpenAt()) && !now.isAfter(performance.getSaleCloseAt());
		boolean scheduleOpen = schedule.getStatus() == ScheduleStatus.ON_SALE && schedule.getStartAt().isAfter(now);
		if (!saleOpen || !scheduleOpen) {
			throw new BusinessException(BookingExceptionCode.SCHEDULE_NOT_ON_SALE);
		}

		Map<Long, String> gradeNames = seatGradeRepository.findByPerformanceId(performance.getId()).stream()
			.collect(Collectors.toMap(SeatGrade::getId, SeatGrade::getName));
		List<CancelPolicyRule> cancelRules =
			cancelPolicyRuleRepository.findByPolicyIdOrderByDaysFromDesc(performance.getCancelPolicy().getId());
		return new BookableSchedule(schedule.getId(), performance, gradeNames, cancelRules);
	}

	/**
	 * 예매 생성에 필요한 회차 정보. 공연은 이미 초기화되어 있어서 트랜잭션이 끝난 뒤에도 값을 읽을 수 있다.
	 *
	 * @param gradeNames  등급 번호 → 등급 이름
	 * @param cancelRules 취소 규정 구간. daysFrom 이 큰 구간부터
	 */
	public record BookableSchedule(Long scheduleId, Performance performance, Map<Long, String> gradeNames,
		List<CancelPolicyRule> cancelRules) {

		public Long performanceId() {
			return performance.getId();
		}
	}
}
