package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.repository.ScheduleSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScheduleSeatService {

	private final ScheduleSeatRepository scheduleSeatRepository;
}
