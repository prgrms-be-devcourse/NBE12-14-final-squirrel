package com.squirrel.tickting.domain.queue.controller;

import com.squirrel.tickting.domain.queue.dto.QueueResponse;
import com.squirrel.tickting.domain.queue.facade.QueueFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 예매자의 회차 대기열. 예매자만 쓸 수 있다. (SecurityConfig 의 USER_ONLY_PATHS: /api/v1/schedules/{id}/queue) */
@Tag(name = "Queue", description = "예매자 대기열 API")
@RestController
@RequestMapping("/api/v1/schedules/{scheduleId}/queue")
@RequiredArgsConstructor
public class QueueController {

	private final QueueFacade queueFacade;

	@Operation(summary = "대기열 입장(예매하기)",
		description = "회차를 골라 예매하기를 누를 때 부른다. 자리가 있으면 바로 입장(ADMITTED)하고, 없으면 대기(WAITING)와 순번을 준다. "
			+ "이미 줄에 있거나 입장해 있으면 순번·입장 상태를 바꾸지 않고 그대로 준다. "
			+ "판매 중이 아닌 회차는 BOOKING002, 일시 중지한 회차는 BOOKING012, 없는 회차는 BOOKING004 다. 입장해야 구역 요약·구역 좌석 조회와 좌석 점유를 할 수 있다.")
	@PostMapping
	public ResponseEntity<ApiResponse<QueueResponse>> enter(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "회차 번호") @PathVariable Long scheduleId
	) {
		return ResponseEntity.ok(ApiResponse.success(queueFacade.enter(account.getId(), scheduleId)));
	}

	@Operation(summary = "내 대기열 상태",
		description = "대기 중이면 몇 초마다(2~3초 권장) 부른다. 부르는 것이 \"아직 기다리고 있다\"는 표시이고, 30초 동안 부르지 않으면 "
			+ "떠난 것으로 보고 건너뛴다. 줄에도 입장 목록에도 없으면 NONE 이다. (다시 입장해야 한다)")
	@GetMapping
	public ResponseEntity<ApiResponse<QueueResponse>> status(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "회차 번호") @PathVariable Long scheduleId
	) {
		return ResponseEntity.ok(ApiResponse.success(queueFacade.status(account.getId(), scheduleId)));
	}

	@Operation(summary = "대기열 나가기",
		description = "줄에서 나가거나 입장 자리를 반납한다. 줄에 없어도 성공이다.")
	@DeleteMapping
	public ResponseEntity<ApiResponse<Void>> leave(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "회차 번호") @PathVariable Long scheduleId
	) {
		queueFacade.leave(account.getId(), scheduleId);
		return ResponseEntity.ok(ApiResponse.noContentSuccess());
	}
}
