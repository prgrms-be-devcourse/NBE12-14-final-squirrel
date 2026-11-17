package com.squirrel.tickting.domain.performance.controller;

import com.squirrel.tickting.domain.performance.dto.SellerGradeRequest;
import com.squirrel.tickting.domain.performance.dto.SellerGradeResponse;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceRequest;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.facade.SellerPerformanceFacade;
import com.squirrel.tickting.domain.performance.service.GradeService;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Seller - Performance", description = "판매자의 내 공연 API")
@RestController
@RequestMapping("/api/v1/seller/performances")
@RequiredArgsConstructor
public class SellerPerformanceController {

	/** 기본 크기와 최대 크기를 같게 둔다. 더 크게 요청해도 에러 없이 이 값으로 줄어든다. (공통 설정의 최대는 50) */
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 20;

	private final SellerPerformanceFacade sellerPerformanceFacade;
	private final GradeService gradeService;

	/** 사업체 번호를 경로나 쿼리로 받지 않고 토큰에서 꺼낸다. 받으면 남의 사업체 번호를 넣어 볼 수 있기 때문이다. */
	@Operation(summary = "내 공연 목록",
		description = "로그인한 판매자 사업체의 공연 목록이다. 상태를 주지 않으면 취소된 공연까지 모두 나온다. "
			+ "최신 등록이 먼저 나오고(정렬은 고정) 한 번에 최대 20개까지 준다. 판매자 토큰만 쓸 수 있다.")
	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<SellerPerformanceResponse.Summary>>> list(
		@AuthenticationPrincipal CustomUserDetails account,
		@Parameter(description = "공연 상태. 비우면 전체")
		@RequestParam(required = false) PerformanceStatus status,
		@PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
	) {
		// 클라이언트가 보낸 정렬(sort)은 쓰지 않고 페이지 번호와 크기만 받는다.
		Pageable pageRequest = PageRequest.of(pageable.getPageNumber(),
			Math.min(pageable.getPageSize(), MAX_PAGE_SIZE));

		PageResponse<SellerPerformanceResponse.Summary> response = PageResponse.from(
			sellerPerformanceFacade.getList(account.getBusinessId(), status, pageRequest));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/** 사업체 번호와 등록자는 본문으로 받지 않고 토큰에서 꺼낸다. 대표·담당자 모두 등록할 수 있다. */
	@Operation(summary = "공연 등록",
		description = "공연을 작성 중(DRAFT)으로 등록하고 공연장의 모든 구역을 미설정(UNSET)으로 만든다. "
			+ "판매 필드는 비워 둘 수 있고 공개 때 모두 채워져 있어야 한다. "
			+ "취소 규정은 서비스 기본 규정이나 내 사업체 규정만 고를 수 있다.")
	@PostMapping
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.Created>> register(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody SellerPerformanceRequest.Save request
	) {
		SellerPerformanceResponse.Created response = sellerPerformanceFacade.register(account.getBusinessId(),
			account.getId(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@Operation(summary = "내 공연 상세",
		description = "로그인한 판매자 사업체의 공연 상세다. 다른 사업체의 공연이거나 없는 공연은 모두 404 이다.")
	@GetMapping("/{performanceId}")
	public ResponseEntity<ApiResponse<SellerPerformanceResponse.Detail>> detail(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId
	) {
		SellerPerformanceResponse.Detail response = sellerPerformanceFacade.getDetail(account.getBusinessId(),
			performanceId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/** 등급은 공연의 하위 자원이라 공연 컨트롤러에 둔다. 대표·담당자 모두 조회할 수 있다. */
	@Operation(summary = "등급·구역 조회",
		description = "공연의 등급과 구역별 상태·등급이다. locked 가 true 면 취소되지 않은 회차 중 하나라도 판매 좌석이 만들어져 "
			+ "등급·가격·구역 설정을 고칠 수 없다. 등급은 가격 높은 순, 구역은 공연장 구역 순서(sortOrder)순이다. "
			+ "취소된 공연도 조회할 수 있고, 다른 사업체의 공연이거나 없는 공연은 모두 404 이다.")
	@GetMapping("/{performanceId}/grades")
	public ResponseEntity<ApiResponse<SellerGradeResponse.Overview>> grades(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId
	) {
		SellerGradeResponse.Overview response = sellerPerformanceFacade.getGrades(account.getBusinessId(),
			performanceId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	/** 등급만 다루고 다른 도메인을 엮지 않아 파사드 없이 등급 서비스를 바로 부른다. 대표·담당자 모두 추가할 수 있다. */
	@Operation(summary = "등급 추가",
		description = "공연에 좌석 등급을 추가한다. 가격은 0원 이상 10,000,000원 이하이고, 색은 #RRGGBB 형식이거나 비운다. "
			+ "같은 공연에 대소문자·공백을 무시하면 같은 이름의 등급이 있거나, 판매 좌석이 생성되었거나(locked), "
			+ "취소된 공연이면 409 다. 다른 사업체의 공연이거나 없는 공연은 모두 404 이다.")
	@PostMapping("/{performanceId}/grades")
	public ResponseEntity<ApiResponse<SellerGradeResponse.Grade>> addGrade(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId,
		@Valid @RequestBody SellerGradeRequest.Save request
	) {
		SellerGradeResponse.Grade response = gradeService.add(account.getBusinessId(), performanceId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	/** 등급만 다루고 다른 도메인을 엮지 않아 파사드 없이 등급 서비스를 바로 부른다. 대표·담당자 모두 수정할 수 있다. */
	@Operation(summary = "등급 수정",
		description = "등급의 이름·가격·색을 전체 교체한다(색을 빼면 지워진다). 입력 규칙은 등급 추가와 같고 자기 이름은 그대로 써도 된다. "
			+ "이 공연의 등급이 아니면 404, 취소된 공연이거나 같은 이름의 다른 등급이 있거나 판매 좌석이 생성되었으면(locked) 409 다. "
			+ "판매 좌석 생성과 겹치면 잠시 기다리고, 1초 안에 끝나지 않으면 409 COMMON004(잠시 후 다시 시도)다.")
	@PutMapping("/{performanceId}/grades/{gradeId}")
	public ResponseEntity<ApiResponse<SellerGradeResponse.Grade>> updateGrade(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long performanceId,
		@PathVariable Long gradeId,
		@Valid @RequestBody SellerGradeRequest.Save request
	) {
		SellerGradeResponse.Grade response = gradeService.update(account.getBusinessId(), performanceId, gradeId,
			request);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
