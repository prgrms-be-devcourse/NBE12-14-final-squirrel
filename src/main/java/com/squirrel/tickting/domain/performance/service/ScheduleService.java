package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.SchedulePostponementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScheduleService {

	private final PerformanceScheduleRepository performanceScheduleRepository;
	private final SchedulePostponementRepository schedulePostponementRepository;
}
