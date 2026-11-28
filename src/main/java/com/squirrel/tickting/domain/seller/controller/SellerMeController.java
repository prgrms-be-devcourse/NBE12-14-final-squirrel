package com.squirrel.tickting.domain.seller.controller;

import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.facade.PasswordChangeFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 판매자 본인의 계정. 대표·담당자 모두 쓴다. 계정 번호는 경로로 받지 않고 로그인 정보에서 꺼낸다. */
@Tag(name = "SellerMe", description = "판매자 내 계정 API")
@RestController
@RequestMapping("/api/v1/seller/me")
@RequiredArgsConstructor
public class SellerMeController {

	private final PasswordChangeFacade passwordChangeFacade;

	@Operation(summary = "비밀번호 변경",
		description = "대표·담당자 본인의 비밀번호를 바꾼다. 지금 로그인은 그대로다. 현재 비밀번호가 틀리면 400 AUTH311 이고, "
			+ "로그인 실패와 합쳐 여러 번 틀리면 429 AUTH004 로 로그인과 변경이 잠시 막힌다. 새 비밀번호가 현재와 같으면 400 AUTH312 다.")
	@PatchMapping("/password")
	public ResponseEntity<ApiResponse<Void>> changePassword(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody AuthRequest.PasswordChange request,
		HttpServletRequest httpRequest
	) {
		passwordChangeFacade.changeForSeller(account.getId(), request, httpRequest.getRemoteAddr());
		return ResponseEntity.ok(ApiResponse.noContentSuccess());
	}
}
