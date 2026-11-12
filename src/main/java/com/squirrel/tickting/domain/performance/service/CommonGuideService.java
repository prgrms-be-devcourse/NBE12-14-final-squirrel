package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.repository.CommonGuideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CommonGuideService {

	private final CommonGuideRepository commonGuideRepository;
}
