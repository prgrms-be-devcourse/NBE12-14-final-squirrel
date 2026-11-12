package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.service.PerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/performances")
@RequiredArgsConstructor
public class PerformanceController {

	private final PerformanceService performanceService;
}
