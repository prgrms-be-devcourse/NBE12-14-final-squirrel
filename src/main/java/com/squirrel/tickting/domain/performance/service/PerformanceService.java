package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceImageRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceService {

	private final PerformanceRepository performanceRepository;
	private final PerformanceImageRepository performanceImageRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;

	/** 내 사업체의 공연 목록. 상태를 주지 않으면 취소된 공연까지 모든 상태가 나온다. */
	@Transactional(readOnly = true)
	public Page<Performance> searchMine(Long businessId, PerformanceStatus status, Pageable pageable) {
		var statuses = status == null ? EnumSet.allOf(PerformanceStatus.class) : EnumSet.of(status);
		return performanceRepository.search(businessId, statuses, pageable);
	}

	/** 공연별 회차 수. 회차가 없는 공연은 결과에 없으므로 호출하는 쪽이 0으로 본다. */
	@Transactional(readOnly = true)
	public Map<Long, Long> countSchedules(Collection<Long> performanceIds) {
		if (performanceIds.isEmpty()) {
			return Map.of();
		}
		return performanceScheduleRepository.countByPerformanceIds(performanceIds).stream()
			.collect(Collectors.toMap(count -> count.getPerformanceId(), count -> count.getScheduleCount()));
	}
}
