package com.squirrel.tickting.domain.terms.controller;

import com.squirrel.tickting.domain.terms.dto.TermsResponse;
import com.squirrel.tickting.domain.terms.service.TermsService;
import com.squirrel.tickting.domain.terms.type.TermsType;
import com.squirrel.tickting.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Terms", description = "약관 API")
@RestController
@RequestMapping("/api/v1/terms")
@RequiredArgsConstructor
public class TermsController {

	private final TermsService termsService;

	@Operation(summary = "약관 조회",
		description = "가입 화면에 보여 줄 약관을 종류별 최신 버전만 준다. 로그인 없이 호출할 수 있다. "
			+ "type 을 주면 그 종류만 주고, 그 종류의 약관이 없으면 빈 목록이다.")
	@GetMapping
	public ResponseEntity<ApiResponse<List<TermsResponse.Item>>> getLatest(
		@Parameter(description = "약관 종류. 비우면 전체")
		@RequestParam(required = false) TermsType type
	) {
		List<TermsResponse.Item> terms = termsService.getLatest(type).stream()
			.map(TermsResponse.Item::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(terms));
	}
}
