package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.cache.UserPerformanceCache;
import com.squirrel.tickting.domain.performance.dto.UserPerformanceResponse;
import com.squirrel.tickting.domain.performance.service.UserPerformanceService;
import com.squirrel.tickting.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예매자 화면의 회차 좌석 조회. 로그인 없이 볼 수 있다. (SecurityConfig 의 PUBLIC_READ_PATHS)
 * 좌석은 구역 요약 → 구역 좌석 상세 2단계로 본다.
 */
@Tag(name = "Performance", description = "예매자 공연 조회 API")
@RestController
@RequestMapping("/api/v1/schedules")
@RequiredArgsConstructor
public class UserScheduleSeatController {

	/** 구역 요약만 몇 초 낡아도 되어서 읽기 캐시를 거친다. 구역 좌석 상세는 좌석 상태라 항상 서비스에서 읽는다. */
	private final UserPerformanceCache userPerformanceCache;
	private final UserPerformanceService userPerformanceService;

	@Operation(summary = "구역 요약",
		description = "회차의 구역별 등급·가격과 잔여석을 공연장의 화면 순서대로 준다. 공개된 공연의 취소되지 않은 회차만 볼 수 있고 그 밖은 404 다. "
			+ "판매하지 않는 구역은 등급·색·가격이 null 이고, 판매 좌석을 아직 만들지 않은 회차는 잔여석이 모두 0 이다.")
	@GetMapping("/{scheduleId}/zones")
	public ResponseEntity<ApiResponse<UserPerformanceResponse.ZoneSummary>> zones(
		@Parameter(description = "회차 번호")
		@PathVariable Long scheduleId
	) {
		UserPerformanceResponse.ZoneSummary response = userPerformanceCache.getZoneSummary(scheduleId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "구역 좌석 상세",
		description = "회차의 한 구역에 있는 좌석을 표시 순서대로 상태와 함께 준다. seatId 를 좌석 점유에 보낸다. "
			+ "볼 수 없는 회차, 그 회차 공연장의 구역이 아닌 구역, 판매 좌석을 아직 만들지 않은 회차는 404 다.")
	@GetMapping("/{scheduleId}/zones/{zoneId}/seats")
	public ResponseEntity<ApiResponse<UserPerformanceResponse.ZoneSeats>> seats(
		@Parameter(description = "회차 번호")
		@PathVariable Long scheduleId,
		@Parameter(description = "구역 번호")
		@PathVariable Long zoneId
	) {
		UserPerformanceResponse.ZoneSeats response = userPerformanceService.getZoneSeats(scheduleId, zoneId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
