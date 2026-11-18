package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.dto.SellerGradeRequest;
import com.squirrel.tickting.domain.performance.dto.SellerGradeResponse;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.SeatGradeRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ZoneGradeRepository;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
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
	 * 내 공연의 등급과 구역 설정.
	 * 취소된 공연도 지난 판매 설정을 확인할 수 있게 막지 않는다.
	 * locked 는 취소되지 않은 회차 중 하나라도 판매 좌석을 만들었으면 true 다. 가격은 좌석을 만들 때 복사되므로
	 * 일부 회차만 만든 상태에서 고치면 같은 공연의 회차끼리 가격이 어긋난다. 등급 추가·수정(SLR-09·10·11)도 같은 기준을 쓴다.
	 */
	@Transactional(readOnly = true)
	public GradeOverview getOverview(Long businessId, Long performanceId) {
		Performance performance = getMine(businessId, performanceId);
		return new GradeOverview(
			performance,
			seatGradeRepository.findByPerformanceIdOrderByPriceDescIdAsc(performanceId),
			zoneGradeRepository.findAllWithGradeByPerformanceId(performanceId),
			performanceScheduleRepository.existsSeatsGenerated(performanceId, ScheduleStatus.CANCELED));
	}

	/**
	 * 내 공연에 등급을 추가한다. 남의 공연인지 먼저 보고 404 로 끝내서 남의 공연 상태(취소·locked)를 드러내지 않는다.
	 * 락은 쓰지 않는다. 새 등급은 아직 어느 구역에도 지정되지 않아 판매 좌석 생성이 그 가격을 복사할 일이 없고,
	 * 같은 이름의 동시 추가는 나중에 만들 등급명 UNIQUE 식 인덱스가 마지막 방어선이다. (취소 규정 이름과 같은 방식)
	 */
	@Transactional
	public SellerGradeResponse.Grade add(Long businessId, Long performanceId, SellerGradeRequest.Save request) {
		Performance performance = getMine(businessId, performanceId);
		if (performance.getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED);
		}
		if (seatGradeRepository.existsSameName(performanceId, request.name())) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_NAME_DUPLICATED);
		}
		if (performanceScheduleRepository.existsSeatsGenerated(performanceId, ScheduleStatus.CANCELED)) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_LOCKED);
		}

		SeatGrade grade = seatGradeRepository.save(SeatGrade.builder()
			.performance(performance)
			.name(request.name())
			.price(request.price())
			.color(request.color())
			.build());
		return SellerGradeResponse.Grade.from(grade);
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

	/** 내 공연. 남의 공연은 번호가 있는지 알려주지 않으려고 없는 공연과 같은 404 로 답한다. */
	private Performance getMine(Long businessId, Long performanceId) {
		return performanceRepository.findByIdAndBusinessId(performanceId, businessId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND));
	}
}
