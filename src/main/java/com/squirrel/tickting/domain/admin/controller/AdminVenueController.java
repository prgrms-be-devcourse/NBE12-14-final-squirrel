package com.squirrel.tickting.domain.admin.controller;

import com.squirrel.tickting.domain.admin.dto.AdminVenueRequest;
import com.squirrel.tickting.domain.admin.dto.AdminVenueResponse;
import com.squirrel.tickting.domain.admin.facade.AdminVenueFacade;
import com.squirrel.tickting.domain.venue.service.VenueService;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.response.PageResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin - Venue", description = "관리자의 공연장 관리 API")
@RestController
@RequestMapping("/api/v1/admin/venues")
@RequiredArgsConstructor
public class AdminVenueController {

	/** 기본 크기와 최대 크기를 같게 둔다. 판매자 공연장 목록과 같다. */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final VenueService venueService;
	/** 상태를 바꾸거나 여러 도메인의 값을 모으는 일은 파사드가 한다. 목록은 서비스를 바로 부른다. */
	private final AdminVenueFacade adminVenueFacade;

	@Operation(summary = "공연장 목록",
		description = "모든 상태의 공연장을 이름순(같으면 번호순)으로 준다. 상태를 주면 그 상태만, 지역은 정확히 같은 것만, "
			+ "검색어는 이름에서 부분 일치로 찾는다. 한 번에 최대 20개까지 준다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<AdminVenueResponse.Summary>>> list(
		@Parameter(description = "상태. 비우면 전체")
		@RequestParam(required = false) VenueStatus status,
		@Parameter(description = "지역. 정확히 같은 것만. 비우면 전체", example = "서울")
		@RequestParam(required = false) String region,
		@Parameter(description = "공연장 이름 검색어. 비우면 전체")
		@RequestParam(required = false) String keyword,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 클라이언트가 보낸 정렬(sort)은 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		var page = venueService.searchAll(status, region, keyword, pageRequest)
			.map(AdminVenueResponse.Summary::from);
		PageResponse<AdminVenueResponse.Summary> response = PageResponse.from(page);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "공연장 사용 중지 영향 미리보기",
		description = "지금 전부 취소하면 취소될 회차, 환불 대상 예매, 영향을 받는 공연과 사업체를 준다. 읽기만 한다. 없는 공연장은 404 다.")
	@GetMapping("/{venueId}/impact")
	public ResponseEntity<ApiResponse<AdminVenueResponse.Impact>> impact(@PathVariable Long venueId) {
		AdminVenueResponse.Impact response = adminVenueFacade.getImpact(venueId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "공연장 사용 중지",
		description = "mode 가 BLOCK_NEW 면 새 일정(공연 등록·공연장 변경·공개·회차 추가)만 막는다. CANCEL_ALL 이면 이 공연장 공개 공연의 "
			+ "시작하지 않은 회차(일정 미정 포함)를 모두 취소하고, 남은 회차가 없는 공연도 취소한다. 취소는 되돌릴 수 없고 환불은 이 요청 뒤에 "
			+ "처리한다. 이미 중지된 공연장에 BLOCK_NEW 는 409, CANCEL_ALL 은 취소할 회차가 있으면 가능하고 없으면 409 다. "
			+ "같은 공연장·공연의 다른 변경과 겹쳐 1초 안에 끝나지 않으면 409 COMMON004(잠시 후 다시 시도)다.")
	@PostMapping("/{venueId}/deactivate")
	public ResponseEntity<ApiResponse<AdminVenueResponse.Deactivated>> deactivate(
		@AuthenticationPrincipal CustomUserDetails admin,
		@PathVariable Long venueId,
		@Valid @RequestBody AdminVenueRequest.Deactivate request
	) {
		AdminVenueResponse.Deactivated response = adminVenueFacade.deactivate(admin.getId(), venueId, request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(summary = "공연장 사용 재개",
		description = "중지한 공연장을 다시 쓴다. 전부 취소로 취소한 회차와 공연은 되돌리지 않는다. 사용 중이면 409, 없으면 404 다.")
	@PostMapping("/{venueId}/activate")
	public ResponseEntity<ApiResponse<AdminVenueResponse.Activated>> activate(
		@AuthenticationPrincipal CustomUserDetails admin,
		@PathVariable Long venueId
	) {
		AdminVenueResponse.Activated response = adminVenueFacade.activate(admin.getId(), venueId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
