package com.squirrel.tickting.domain.seller.controller;

import com.squirrel.tickting.domain.seller.dto.SellerMemberResponse;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 담당자 관리. 판매자 대표만 쓴다. (/api/v1/seller/** 라서 SecurityConfig 의 판매자 규칙에 이미 들어 있다)
 * 사업체 번호는 로그인 정보에서 꺼내서, 다른 회사 계정은 없는 계정과 같은 404 다.
 */
@Tag(name = "SellerMember", description = "담당자 관리 API (대표)")
@RestController
@RequestMapping("/api/v1/seller/members")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SELLER_OWNER')")
public class SellerMemberController {

	private final SellerMemberService sellerMemberService;

	@Operation(summary = "담당자 목록 (대표)", description = "내 회사의 모든 계정(대표 포함, 비활성 포함). 대표가 먼저, 나머지는 만든 순서다.")
	@GetMapping
	public ResponseEntity<ApiResponse<List<SellerMemberResponse.Member>>> getMembers(
		@AuthenticationPrincipal CustomUserDetails account
	) {
		return ResponseEntity.ok(ApiResponse.success(sellerMemberService.getMembers(account.getBusinessId())));
	}

	@Operation(summary = "담당자 비활성화 (대표)",
		description = "계정은 지우지 않고 비활성으로 바꾼다. 이미 로그인해 있던 세션도 다음 요청에서 401(AUTH003)이 된다. "
			+ "없거나 다른 회사 계정이면 404(SELLER009), 대표 계정이면 400(SELLER010), 이미 비활성이면 409(SELLER011)이다.")
	@PostMapping("/{memberId}/deactivate")
	public ResponseEntity<ApiResponse<SellerMemberResponse.Member>> deactivate(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long memberId
	) {
		return ResponseEntity.ok(ApiResponse.success(sellerMemberService.deactivate(account.getBusinessId(), memberId)));
	}

	@Operation(summary = "담당자 복구 (대표)",
		description = "비활성 담당자를 사용 중으로 바꾼다. 비밀번호는 예전 그대로다. 없거나 다른 회사 계정이면 404(SELLER009), 이미 활성이면 409(SELLER012), "
			+ "그 이메일의 활성 계정이 다른 회사에 있으면 409(SELLER013)이다.")
	@PostMapping("/{memberId}/reactivate")
	public ResponseEntity<ApiResponse<SellerMemberResponse.Member>> reactivate(
		@AuthenticationPrincipal CustomUserDetails account,
		@PathVariable Long memberId
	) {
		return ResponseEntity.ok(ApiResponse.success(sellerMemberService.reactivate(account.getBusinessId(), memberId)));
	}
}
