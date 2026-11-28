package com.squirrel.tickting.domain.admin.controller;

import com.squirrel.tickting.domain.admin.dto.AdminCommonGuideRequest;
import com.squirrel.tickting.domain.admin.dto.AdminCommonGuideResponse;
import com.squirrel.tickting.domain.admin.facade.AdminCommonGuideFacade;
import com.squirrel.tickting.domain.performance.service.CommonGuideService;
import com.squirrel.tickting.domain.performance.type.GuideType;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 모든 공연 상세에 똑같이 붙는 공통 안내(예매 안내, 환불 안내)를 관리자가 보고 고친다. */
@Tag(name = "Admin - Common Guide", description = "관리자의 공통 안내 관리 API")
@RestController
@RequestMapping("/api/v1/admin/common-guides")
@RequiredArgsConstructor
public class AdminCommonGuideController {

	private final CommonGuideService commonGuideService;
	/** 수정은 감사 로그와 함께 남겨야 해서 파사드가 한다. 목록은 서비스를 바로 부른다. */
	private final AdminCommonGuideFacade adminCommonGuideFacade;

	@Operation(summary = "공통 안내 목록",
		description = "유형마다 한 줄씩 준다. 아직 넣지 않은 유형도 content 를 null 로 함께 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<List<AdminCommonGuideResponse.Guide>>> list() {
		List<AdminCommonGuideResponse.Guide> response =
			AdminCommonGuideResponse.Guide.listOf(commonGuideService.getGuides());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "공통 안내 수정",
		description = "그 유형의 내용을 통째로 바꾼다. 아직 없으면 새로 만든다. 공연 상세에는 다음 조회부터 바로 보인다. "
			+ "바뀌기 전·후 내용을 감사 로그에 남긴다. 없는 유형은 400 이다.")
	@PutMapping("/{type}")
	public ResponseEntity<ApiResponse<AdminCommonGuideResponse.Guide>> update(
		@AuthenticationPrincipal CustomUserDetails admin,
		@Parameter(description = "안내 유형 (BOOKING_GUIDE 예매 안내 / REFUND_GUIDE 환불 안내)")
		@PathVariable GuideType type,
		@Valid @RequestBody AdminCommonGuideRequest.Update request
	) {
		AdminCommonGuideResponse.Guide response = adminCommonGuideFacade.update(admin.getId(), type,
			request.content());
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
