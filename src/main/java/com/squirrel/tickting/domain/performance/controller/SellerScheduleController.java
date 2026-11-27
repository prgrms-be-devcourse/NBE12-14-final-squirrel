package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.dto.SellerPerformanceRequest;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.facade.SellerPerformanceFacade;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller - Schedule", description = "판매자의 회차 API")
@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerScheduleController {

	private final SellerPerformanceService sellerPerformanceService;
	private final SellerPerformanceFacade sellerPerformanceFacade;

	/** 감사 로그를 함께 남겨 파사드를 부른다. 대표·담당자 모두 추가할 수 있다. */
	@Operation(summary = "회차 추가",
		description = "공연에 회차를 하나 추가한다. 시작 시각은 지금보다 늦어야 하고(400), 판매 오픈이 설정되어 있으면 그보다 늦어야 한다(400). "
			+ "작성 중(DRAFT) 공연은 지금부터 3시간 뒤의 회차부터 추가할 수 있다(400). 공개(PUBLISHED) 공연은 회차가 모두 시작했으면 409, 판매가 끝났으면 400이다. "
			+ "취소된 공연(409), 사용이 중지된 공연장(400), 같은 공연장의 다른 회차와 시간이 겹치는 경우(409, 시작부터 러닝타임 + 1시간)는 막는다. "
			+ "겹친 회차가 내 사업체 것이면 메시지에 공연명과 일시를 알려 준다. "
			+ "공개 공연이고 판매 오픈 24시간 전부터는 판매 좌석을 바로 만든다. 만든 순간부터 그 공연의 등급·가격·구역을 고칠 수 없고 되돌릴 수 없으며, "
			+ "설정하지 않은 구역이 있거나 판매 구역이 없으면 400이고 회차도 추가되지 않는다. "
			+ "공개 공연의 아직 시작하지 않은 회차가 모두 일시 중지면 409 PERFORMANCE018 이고 추가하지 않는다. "
			+ "다른 사업체의 공연이거나 없는 공연은 404 다. 같은 공연의 다른 변경과 겹쳐 1초 안에 끝나지 않으면 409 COMMON004(잠시 후 다시 시도)다.")
	@PostMapping("/performances/{performanceId}/schedules")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.ScheduleCreated>> addSchedule(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId,
		@Valid @RequestBody SellerPerformanceRequest.ScheduleSave request
	) {
		SellerPerformanceResponse.ScheduleCreated response = sellerPerformanceFacade.addSchedule(account.getBusinessId(),
			account.getId(), performanceId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	/** 예매 확인이 예매 도메인이라 파사드를 부른다. 대표·담당자 모두 바꿀 수 있다. */
	@Operation(summary = "회차 수정",
		description = "회차의 공연 일시를 바꾼다. 판매 중·일시 중지이고 아직 시작하지 않은 회차만 바꿀 수 있다. 취소된 공연·취소 회차·연기 회차는 409, "
			+ "이미 시작한 회차는 400이다. 지금과 같은 일시를 보내면 아무것도 바꾸지 않고 200이다. "
			+ "새 일시는 지금보다 늦어야 하고(400), 판매 오픈이 설정되어 있으면 그보다 늦어야 하며(400), 작성 중(DRAFT) 공연은 지금부터 3시간 뒤여야 한다(400). "
			+ "공개(PUBLISHED) 공연은 만료가 아닌 예매(취소 포함)가 있으면 409, 바꾼 뒤 판매 종료가 마지막 회차 시작보다 늦어지면 400이다. "
			+ "같은 공연장의 다른 회차와 시간이 겹치면 409다. 회차 상태와 판매 좌석은 바뀌지 않고 바꾸기 전 일시는 감사 로그에 남는다. "
			+ "다른 사업체의 회차이거나 없는 회차는 404 다. 같은 공연의 다른 변경과 겹쳐 1초 안에 끝나지 않으면 409 COMMON004(잠시 후 다시 시도)다.")
	@PutMapping("/schedules/{scheduleId}")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.ScheduleUpdated>> updateSchedule(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long scheduleId,
		@Valid @RequestBody SellerPerformanceRequest.ScheduleSave request
	) {
		SellerPerformanceResponse.ScheduleUpdated response = sellerPerformanceFacade.updateSchedule(account.getBusinessId(),
			account.getId(), scheduleId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/** 감사 로그를 함께 남겨 파사드를 부른다. 대표·담당자 모두 중지할 수 있다. */
	@Operation(summary = "회차 판매 일시 중지",
		description = "공개(PUBLISHED) 공연의 회차 판매를 모두(all: true) 또는 골라서(scheduleIds) 일시 중지한다. 둘 중 하나만 보낸다. "
			+ "중지 사유(reason)는 필수이고 예매자 공연 상세에 보인다. 판매 중이고 아직 시작하지 않은 회차만 바꾸고 나머지는 skipped 에 이유와 함께 준다. "
			+ "중지하면 대기열 입장·좌석 점유·결제 준비와 승인이 막힌다. 중지 전에 승인을 시작한 결제는 완료된다. "
			+ "취소된 공연·작성 중 공연·바꿀 회차가 하나도 없으면 409, 다른 사업체의 공연(404)이나 이 공연의 회차가 아닌 번호가 섞이면(404) "
			+ "아무것도 바꾸지 않는다. 같은 공연의 다른 변경과 겹쳐 1초 안에 끝나지 않으면 409 COMMON004(잠시 후 다시 시도)다.")
	@PostMapping("/performances/{performanceId}/schedules/pause")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.ScheduleSaleChanged>> pauseSchedules(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId,
		@Valid @RequestBody SellerPerformanceRequest.SchedulePause request
	) {
		SellerPerformanceResponse.ScheduleSaleChanged response = sellerPerformanceFacade.pauseSchedules(
			account.getBusinessId(), account.getId(), performanceId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/** 감사 로그를 함께 남겨 파사드를 부른다. 대표·담당자 모두 재개할 수 있다. */
	@Operation(summary = "회차 판매 재개",
		description = "일시 중지한 회차를 모두(all: true) 또는 골라서(scheduleIds) 다시 판매 중으로 바꾼다. 모두 재개는 따로 중지한 회차도 연다. "
			+ "일시 중지이고 아직 시작하지 않은 회차만 바꾸고 나머지는 skipped 에 이유와 함께 준다. 중지 사유는 비운다. "
			+ "검사와 에러는 판매 일시 중지와 같고, 바꿀 회차가 하나도 없으면 409다.")
	@PostMapping("/performances/{performanceId}/schedules/resume")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.ScheduleSaleChanged>> resumeSchedules(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId,
		@Valid @RequestBody SellerPerformanceRequest.ScheduleResume request
	) {
		SellerPerformanceResponse.ScheduleSaleChanged response = sellerPerformanceFacade.resumeSchedules(
			account.getBusinessId(), account.getId(), performanceId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/** 감사 로그를 함께 남겨 파사드를 부른다. 대표·담당자 모두 연기할 수 있다. */
	@Operation(summary = "회차 연기",
		description = "공개(PUBLISHED) 공연의 아직 시작하지 않은 판매 중·일시 중지 회차를 연기한다. newStartAt 을 비우면 일정 미정(POSTPONED)이 되어 "
			+ "대기열 입장·좌석 점유·결제가 막히고, 넣으면 상태는 그대로 두고 공연 일시만 바꾼다(앞당기기도 된다). 연기 전에 만든 결제 대기 예매는 결제할 수 없다. "
			+ "예매는 바꾸지 않고, 연기 전에 예매한 사람이 직접 취소하면 수수료가 없다. 일정 미정이면 확정 전까지, 날짜를 바꾸면 freeCancelUntil 까지다. "
			+ "사유(reason)는 필수이고 예매자 공연 상세에 보인다. freeCancelUntil 은 newStartAt 이 있을 때만 보내며, 비우면 14일 뒤(남은 이전 마감이 "
			+ "더 늦으면 그때, 새 일시를 넘지 않음)다. 보내면 새 일시 이하이고 7일 뒤(남은 이전 마감이 더 늦으면 그때, 새 일시가 이르면 새 일시) 이상이어야 한다. "
			+ "취소된 공연·작성 중 공연·취소 회차·일정 미정 회차는 409, 이미 시작한 회차·지금과 같은 일시·판매 오픈 이전·판매 종료가 마지막 회차보다 "
			+ "늦어지는 일시·마감 범위 밖은 400, 같은 공연장 시간 겹침은 409다. 다른 사업체의 회차이거나 없는 회차는 404 다. "
			+ "같은 공연의 다른 변경과 겹쳐 1초 안에 끝나지 않으면 409 COMMON004(잠시 후 다시 시도)다.")
	@PostMapping("/schedules/{scheduleId}/postpone")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.SchedulePostponed>> postponeSchedule(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long scheduleId,
		@Valid @RequestBody SellerPerformanceRequest.SchedulePostpone request
	) {
		SellerPerformanceResponse.SchedulePostponed response = sellerPerformanceFacade.postponeSchedule(
			account.getBusinessId(), account.getId(), scheduleId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/** 감사 로그를 함께 남겨 파사드를 부른다. 대표·담당자 모두 확정할 수 있다. */
	@Operation(summary = "연기 회차 새 일정 확정",
		description = "일정 미정(POSTPONED)인 회차의 새 공연 일시를 정하고 판매 중으로 바꾼다. 중지한 상태에서 연기했어도 판매 중이 된다. "
			+ "원래 일시를 다시 골라도 된다. 연기 전에 예매한 사람은 freeCancelUntil 까지 수수료 없이 취소할 수 있고, 비우면 14일 뒤(새 일시를 넘지 않음)다. "
			+ "보내면 새 일시 이하이고 7일 뒤(새 일시가 이르면 새 일시) 이상이어야 한다. 새 일시의 조건과 404·409 COMMON004 는 회차 연기와 같고, "
			+ "일정 미정 회차가 아니면 409 PERFORMANCE021 이다.")
	@PostMapping("/schedules/{scheduleId}/postpone/confirm")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.SchedulePostponed>> confirmPostponement(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long scheduleId,
		@Valid @RequestBody SellerPerformanceRequest.SchedulePostponeConfirm request
	) {
		SellerPerformanceResponse.SchedulePostponed response = sellerPerformanceFacade.confirmPostponement(
			account.getBusinessId(), account.getId(), scheduleId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
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
