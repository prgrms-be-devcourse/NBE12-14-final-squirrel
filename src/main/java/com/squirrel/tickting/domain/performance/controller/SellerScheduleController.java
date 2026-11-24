package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerScheduleController {

	private final ScheduleService scheduleService;
}
