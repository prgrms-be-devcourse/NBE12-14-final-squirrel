package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class ScheduleSeatController {

	private final ScheduleSeatService scheduleSeatService;
}
