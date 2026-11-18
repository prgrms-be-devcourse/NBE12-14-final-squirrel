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
import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(PerformanceProperties.class)
public class GradeService {

	private final SeatGradeRepository seatGradeRepository;
	private final ZoneGradeRepository zoneGradeRepository;
	private final PerformanceRepository performanceRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;
	private final DistributedLockExecutor lockExecutor;
	private final TransactionTemplate transactionTemplate;
	private final PerformanceProperties properties;

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
			isLocked(performanceId));
	}

	/**
	 * 내 공연에 등급을 추가한다. 남의 공연인지 먼저 보고 404 로 끝내서 남의 공연 상태(취소·locked)를 드러내지 않는다.
	 * 락은 쓰지 않는다. 새 등급은 아직 어느 구역에도 지정되지 않아 판매 좌석 생성이 그 가격을 복사할 일이 없고,
	 * 같은 등급의 동시 추가는 나중에 만들 UNIQUE (performance_id, name) 가 마지막 방어선이다. (취소 규정 이름과 같은 방식)
	 * 전석도 다른 등급과 함께 만들 수 있다. 구역에 지정되기 전에는 예매자 화면에 보이지 않기 때문이다.
	 */
	@Transactional
	public SellerGradeResponse.Grade add(Long businessId, Long performanceId, SellerGradeRequest.Save request) {
		Performance performance = getMine(businessId, performanceId);
		if (performance.getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED);
		}
		if (seatGradeRepository.existsByPerformanceIdAndName(performanceId, request.name())) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_NAME_DUPLICATED);
		}
		if (isLocked(performanceId)) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_LOCKED);
		}

		SeatGrade grade = seatGradeRepository.save(SeatGrade.builder()
			.performance(performance)
			.name(request.name())
			.price(request.price())
			.build());
		return SellerGradeResponse.Grade.from(grade);
	}

	/**
	 * 내 공연의 등급을 전체 교체로 고친다. 다른 도메인을 엮지 않아 파사드 없이 이 서비스가 락과 트랜잭션을 직접 연다.
	 * 락은 판매 좌석 생성이 고치기 전 가격을 복사하는 것을 막으려는 것이다. 좌석 생성도 같은 이름의 락을 잡아야 효과가 있다.
	 * 락은 커밋 뒤에 풀려야 해서 이 메서드에는 @Transactional 을 붙이지 않는다.
	 * 남의 공연은 락을 잡기 전에 404 로 끝내서 남의 공연 상태(취소·locked)를 드러내지 않는다.
	 */
	public SellerGradeResponse.Grade update(Long businessId, Long performanceId, Long gradeId,
		SellerGradeRequest.Save request) {
		getMine(businessId, performanceId);
		return lockExecutor.execute(List.of(lockName(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> updateInTransaction(businessId, performanceId, gradeId, request)));
	}

	/** 락을 기다리는 사이에 공연이 취소되거나 좌석이 만들어졌을 수 있어서 락 밖의 판단은 믿지 않고 다시 읽는다. */
	private SellerGradeResponse.Grade updateInTransaction(Long businessId, Long performanceId, Long gradeId,
		SellerGradeRequest.Save request) {
		Performance performance = getMine(businessId, performanceId);
		if (performance.getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED);
		}
		SeatGrade grade = seatGradeRepository.findByIdAndPerformanceId(gradeId, performanceId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.GRADE_NOT_FOUND));
		if (seatGradeRepository.existsByPerformanceIdAndNameAndIdNot(performanceId, request.name(), gradeId)) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_NAME_DUPLICATED);
		}
		if (mixesAllAfterRename(performanceId, grade, request.name())) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_ALL_MIXED);
		}
		if (isLocked(performanceId)) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_LOCKED);
		}

		grade.update(request.name(), request.price());
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

	/**
	 * 이 등급을 newName 으로 바꾸면 전석과 다른 등급이 함께 구역에 지정되는가.
	 * 등급이 바뀌지 않거나 어느 구역에도 지정되지 않은 등급이면 예매자 화면이 달라지지 않아 보지 않는다.
	 */
	private boolean mixesAllAfterRename(Long performanceId, SeatGrade grade, SeatGradeName newName) {
		if (grade.getName() == newName) {
			return false;
		}
		List<SeatGrade> assigned = zoneGradeRepository.findAssignedGrades(performanceId);
		if (assigned.stream().noneMatch(assignedGrade -> assignedGrade.getId().equals(grade.getId()))) {
			return false;
		}
		Set<SeatGradeName> names = assigned.stream()
			.map(assignedGrade -> assignedGrade.getId().equals(grade.getId()) ? newName : assignedGrade.getName())
			.collect(Collectors.toSet());
		return names.contains(SeatGradeName.ALL) && names.size() > 1;
	}

	/** 취소되지 않은 회차 중 하나라도 판매 좌석을 만들었는가. 조회의 locked 와 추가·수정의 409 가 같은 기준을 쓰게 한 곳에 둔다. */
	private boolean isLocked(Long performanceId) {
		return performanceScheduleRepository.existsSeatsGenerated(performanceId, ScheduleStatus.CANCELED);
	}

	/**
	 * 공연 단위 락 이름. 판매 좌석 생성(SLR-14, 스케줄러)도 같은 이름을 잡아야 등급 가격 수정과 좌석의 가격 복사가 겹치지 않는다.
	 * (좌석 생성 쪽과 이름을 맞추는 중이다)
	 */
	private static String lockName(Long performanceId) {
		return "performance:" + performanceId;
	}
}
