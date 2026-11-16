package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.infra.repository.SeatGradeRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ZoneGradeRepository;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GradeService {

	private final SeatGradeRepository seatGradeRepository;
	private final ZoneGradeRepository zoneGradeRepository;

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
