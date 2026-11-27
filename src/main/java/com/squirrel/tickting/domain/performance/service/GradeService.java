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
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(PerformanceProperties.class)
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
	public SellerGradeResponse.Overview getOverview(Long businessId, Long performanceId) {
		Performance performance = getMine(businessId, performanceId);
		return SellerGradeResponse.Overview.from(performance,
			seatGradeRepository.findByPerformanceIdOrderByPriceDescIdAsc(performanceId),
			zoneGradeRepository.findAllWithGradeByPerformanceId(performanceId),
			isLocked(performanceId));
	}

	/**
	 * 내 공연에 등급을 추가한다. 남의 공연인지 먼저 보고 404 로 끝내서 남의 공연 상태(취소·locked)를 드러내지 않는다.
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

	/** 이 공연의 등급 한 건. 감사 로그에 고치기 전 값을 남길 때 쓴다. 내 공연인지는 호출한 쪽이 먼저 확인한다. */
	@Transactional(readOnly = true)
	public Optional<SellerGradeResponse.Grade> findGrade(Long performanceId, Long gradeId) {
		return seatGradeRepository.findByIdAndPerformanceId(gradeId, performanceId).map(SellerGradeResponse.Grade::from);
	}

	/**
	 * 내 공연의 등급을 전체 교체로 고친다. 호출한 쪽(파사드)이 공연 락을 잡고 트랜잭션을 연 상태에서 부른다.
	 * 락을 기다리는 사이에 공연이 취소되거나 좌석이 만들어졌을 수 있어서 락 밖의 판단은 믿지 않고 다시 읽는다.
	 */
	@Transactional
	public SellerGradeResponse.Grade update(Long businessId, Long performanceId, Long gradeId,
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
	 * 같은 구역이 두 번 오면 어느 값이 맞는지 서버가 추측하지 않고 400 으로 돌려보낸다. (프론트 버그를 드러낸다)
	 * 요청 안에서만 판단해서 남의 공연에 보내도 남의 공연 정보가 드러나지 않는다.
	 */
	public void validateAssignments(List<SellerGradeRequest.Item> assignments) {
		Set<Long> zoneIds = new HashSet<>();
		for (SellerGradeRequest.Item item : assignments) {
			if (!zoneIds.add(item.zoneId())) {
				throw new BusinessException(CommonExceptionCode.INVALID_INPUT);
			}
		}
		for (SellerGradeRequest.Item item : assignments) {
			boolean assigned = item.assignmentStatus() == ZoneAssignmentStatus.ASSIGNED;
			if (assigned != (item.gradeId() != null)) {
				throw new BusinessException(PerformanceExceptionCode.ZONE_ASSIGNMENT_INVALID);
			}
		}
	}

	/**
	 * 내 공연의 구역 상태와 등급을 요청에 있는 구역만 바꾼다. 하나라도 틀리면 아무것도 바꾸지 않는다(전체 적용 또는 전체 거절).
	 * 일부만 적용되면 판매자가 어느 구역이 바뀌었는지 알기 어렵기 때문이다.
	 * 호출한 쪽(파사드)이 요청 검사(validateAssignments)를 먼저 하고, 공연 락을 잡고 트랜잭션을 연 상태에서 부른다.
	 * 좌석 생성이 먼저 끝나면 수정은 locked 409 로 거절되어 판매자가 사실대로 안다.
	 */
	@Transactional
	public SellerGradeResponse.Overview updateZoneGrades(Long businessId, Long performanceId,
		SellerGradeRequest.ZoneAssign request) {
		Performance performance = getMine(businessId, performanceId);
		if (performance.getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED);
		}
		List<ZoneGrade> zoneGrades = zoneGradeRepository.findAllWithGradeByPerformanceId(performanceId);
		List<SeatGrade> grades = seatGradeRepository.findByPerformanceIdOrderByPriceDescIdAsc(performanceId);
		// 구역 설정 행은 공연을 등록할 때 공연장의 구역마다 만들어서, 행이 없으면 이 공연의 공연장 구역이 아니다.

		List<SellerGradeRequest.Item> assignments = request.assignments();
		if (assignments.stream().anyMatch(item -> zoneGradeOf(zoneGrades, item.zoneId()) == null)) {
			throw new BusinessException(PerformanceExceptionCode.ZONE_NOT_FOUND_IN_VENUE);
		}
		if (assignments.stream().anyMatch(item -> item.gradeId() != null && gradeOf(grades, item.gradeId()) == null)) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_NOT_IN_PERFORMANCE);
		}
		if (mixesAllAfterAssign(zoneGrades, assignments, grades)) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_ALL_MIXED);
		}
		// 락을 얻은 뒤 여기서 처음 본다. 좌석 생성이 먼저 커밋됐으면 이 검사가 거른다.
		if (isLocked(performanceId)) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_LOCKED);
		}

		// 관리 중인 엔티티라 커밋 때 저장된다. 값이 그대로인 항목도 에러 없이 지나간다.
		for (SellerGradeRequest.Item item : assignments) {
			zoneGradeOf(zoneGrades, item.zoneId()).assign(item.assignmentStatus(),
				item.gradeId() == null ? null : gradeOf(grades, item.gradeId()));
		}
		// 바로 위에서 locked 409 를 통과했으므로 false 다.
		return SellerGradeResponse.Overview.from(performance, grades, zoneGrades, false);
	}

	/**
	 * 요청을 적용한 뒤의 최종 상태에서 전석과 다른 등급이 함께 구역에 지정되는가. 요청에 없는 구역은 기존 값으로 본다.
	 * 구역 하나씩 보면 R·S 구역을 한 요청에서 모두 전석으로 바꾸는 것도 중간에 막히기 때문이다.
	 */
	private boolean mixesAllAfterAssign(List<ZoneGrade> zoneGrades, List<SellerGradeRequest.Item> assignments,
		List<SeatGrade> grades) {
		Set<SeatGradeName> names = new HashSet<>();
		for (ZoneGrade zoneGrade : zoneGrades) {
			SellerGradeRequest.Item item = assignments.stream()
				.filter(candidate -> candidate.zoneId().equals(zoneGrade.getZoneId()))
				.findFirst().orElse(null);
			if (item == null) {
				if (zoneGrade.getAssignmentStatus() == ZoneAssignmentStatus.ASSIGNED) {
					names.add(zoneGrade.getGrade().getName());
				}
			} else if (item.assignmentStatus() == ZoneAssignmentStatus.ASSIGNED) {
				names.add(gradeOf(grades, item.gradeId()).getName());
			}
		}
		return SeatGradeName.mixesAll(names);
	}

	/** 번호로 구역 설정을 찾는다. 구역은 많아야 수십 개라 목록을 훑어도 부담이 없다. 없으면 null 이다. */
	private static ZoneGrade zoneGradeOf(List<ZoneGrade> zoneGrades, Long zoneId) {
		return zoneGrades.stream()
			.filter(zoneGrade -> zoneGrade.getZoneId().equals(zoneId))
			.findFirst().orElse(null);
	}

	/** 번호로 등급을 찾는다. 없으면 null 이다. */
	private static SeatGrade gradeOf(List<SeatGrade> grades, Long gradeId) {
		return grades.stream()
			.filter(grade -> grade.getId().equals(gradeId))
			.findFirst().orElse(null);
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

	/**
	 * 작성 중 공연의 구역 설정을 새 공연장의 모든 구역 기준으로 UNSET 으로 다시 만든다. 등급(seat_grade)은 그대로 둔다.
	 * 공연장이 바뀌면 옛 구역 번호의 설정은 의미가 없고, 새 구역을 빠뜨리면 공개 검사가 "UNSET 없음"으로 잘못 읽는다.
	 * 호출한 쪽의 트랜잭션에 참여한다.
	 */
	@Transactional
	public void resetZones(Performance performance, List<Long> zoneIds) {
		zoneGradeRepository.deleteByPerformanceId(performance.getId());
		initializeZones(performance, zoneIds);
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
		return SeatGradeName.mixesAll(assigned.stream()
			.map(assignedGrade -> assignedGrade.getId().equals(grade.getId()) ? newName : assignedGrade.getName())
			.toList());
	}

	/** 취소되지 않은 회차 중 하나라도 판매 좌석을 만들었는가. 조회의 locked 와 추가·수정의 409 가 같은 기준을 쓰게 한 곳에 둔다. */
	private boolean isLocked(Long performanceId) {
		return performanceScheduleRepository.existsSeatsGenerated(performanceId, ScheduleStatus.CANCELED);
	}
}
