package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.dto.SellerPerformanceRequest;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller - Schedule", description = "판매자의 회차 API")
@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerScheduleController {

	private final SellerPerformanceService sellerPerformanceService;

	/** 공연 도메인 안에서 끝나 파사드 없이 서비스를 바로 부른다. 대표·담당자 모두 추가할 수 있다. */
	@Operation(summary = "회차 추가",
		description = "공연에 회차를 하나 추가한다. 시작 시각은 지금보다 늦어야 하고(400), 판매 오픈이 설정되어 있으면 그보다 늦어야 한다(400). "
			+ "작성 중(DRAFT) 공연은 지금부터 3시간 뒤의 회차부터 추가할 수 있다(400). 공개(PUBLISHED) 공연은 회차가 모두 시작했으면 409, 판매가 끝났으면 400이다. "
			+ "취소된 공연(409), 사용이 중지된 공연장(400), 같은 공연장의 다른 회차와 시간이 겹치는 경우(409, 시작부터 러닝타임 + 1시간)는 막는다. "
			+ "겹친 회차가 내 사업체 것이면 메시지에 공연명과 일시를 알려 준다. "
			+ "공개 공연이고 판매 오픈 24시간 전부터는 판매 좌석을 바로 만든다. 만든 순간부터 그 공연의 등급·가격·구역을 고칠 수 없고 되돌릴 수 없으며, "
			+ "설정하지 않은 구역이 있거나 판매 구역이 없으면 400이고 회차도 추가되지 않는다. "
			+ "다른 사업체의 공연이거나 없는 공연은 404 다. 같은 공연의 다른 변경과 겹쳐 1초 안에 끝나지 않으면 409 COMMON004(잠시 후 다시 시도)다.")
	@PostMapping("/performances/{performanceId}/schedules")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.ScheduleCreated>> addSchedule(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId,
		@Valid @RequestBody SellerPerformanceRequest.ScheduleSave request
	) {
		SellerPerformanceResponse.ScheduleCreated response = sellerPerformanceService.addSchedule(account.getBusinessId(),
			performanceId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	/** 공연 도메인 안에서 끝나 파사드 없이 서비스를 바로 부른다. 대표·담당자 모두 만들 수 있다. */
	@Operation(summary = "판매 좌석 생성",
		description = "한 회차의 판매 좌석을 공연장 좌석에서 복사해 만든다. 작성 중(DRAFT) 공연은 판매 오픈·종료·1인 최대 매수·수령 방법·예매 수수료가 "
			+ "모두 설정되어 있어야 하고(아니면 400), 공개(PUBLISHED) 공연은 언제든 가능하다. 판매할 구역은 AVAILABLE, 의도적 미판매 구역은 BLOCKED 로 만든다. "
			+ "만든 순간부터 그 공연의 등급·가격·구역을 고칠 수 없고 되돌릴 수 없다. "
			+ "설정하지 않은(UNSET) 구역이 있거나 판매 구역이 하나도 없으면 400, 이미 시작한 회차도 400이다. "
			+ "취소된 공연·취소된 회차·이미 생성된 회차는 409다. 다른 사업체의 회차이거나 없는 회차는 404 다. "
			+ "등급·구역 수정과 겹치면 잠시 기다리고, 1초 안에 끝나지 않으면 409 COMMON004(잠시 후 다시 시도)다.")
	@PostMapping("/schedules/{scheduleId}/seats/generate")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.SeatsGenerated>> generateSeats(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long scheduleId
	) {
		SellerPerformanceResponse.SeatsGenerated response = sellerPerformanceService.generateSeats(account.getBusinessId(),
			scheduleId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
