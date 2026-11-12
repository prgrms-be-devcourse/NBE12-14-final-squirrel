package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.service.GradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller/performances")
@RequiredArgsConstructor
public class SellerGradeController {

	private final GradeService gradeService;
}
