package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.SeatGradeRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ZoneGradeRepository;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GradeService {

	private final SeatGradeRepository seatGradeRepository;
	private final ZoneGradeRepository zoneGradeRepository;
	private final PerformanceRepository performanceRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;

	/**
	 * 내 공연의 등급과 구역 설정. 남의 공연은 없는 공연과 같은 404로 답한다.
	 * 취소된 공연도 지난 판매 설정을 확인할 수 있게 막지 않는다.
	 * locked 는 취소되지 않은 회차 중 하나라도 판매 좌석을 만들었으면 true 다. 가격은 좌석을 만들 때 복사되므로
	 * 일부 회차만 만든 상태에서 고치면 같은 공연의 회차끼리 가격이 어긋난다. 등급 추가·수정(SLR-09·10·11)도 같은 기준을 쓴다.
	 */
	@Transactional(readOnly = true)
	public GradeOverview getOverview(Long businessId, Long performanceId) {
		Performance performance = performanceRepository.findByIdAndBusinessId(performanceId, businessId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND));
		return new GradeOverview(
			performance,
			seatGradeRepository.findByPerformanceIdOrderByPriceDescIdAsc(performanceId),
			zoneGradeRepository.findAllWithGradeByPerformanceId(performanceId),
			performanceScheduleRepository.existsSeatsGenerated(performanceId, ScheduleStatus.CANCELED));
	}

	/**
	 * 새 공연의 구역 설정을 공연장의 모든 구역마다 UNSET 으로 만든다.
	 * 구역을 빠뜨리지 않고 한 번씩 만들어 두어야 공개(SLR-04)가 "UNSET 구역이 남았는지"만 보고 판단할 수 있다.
	 */
	@Transactional
	public void initializeZones(Performance performance, List<Long> zoneIds) {
		List<ZoneGrade> zoneGrades = zoneIds.stream()
			.map(zoneId -> ZoneGrade.builder()
				.performance(performance)
				.zoneId(zoneId)
				.assignmentStatus(ZoneAssignmentStatus.UNSET)
				.build())
			.toList();
		zoneGradeRepository.saveAll(zoneGrades);
	}
}
