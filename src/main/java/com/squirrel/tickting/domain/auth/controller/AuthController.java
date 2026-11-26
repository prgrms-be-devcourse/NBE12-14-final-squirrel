package com.squirrel.tickting.domain.auth.controller;

import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.auth.facade.AuthSessionFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인이 필요 없는 가입 신청. 로그인·로그아웃·예매자 가입은 AuthSessionController(/api/v2)에 있다.
 * 판매자 가입은 로그인(세션)을 만들지 않아서 경로를 v1 그대로 두었다. (프론트가 이 경로를 쓴다)
 */
@Tag(name = "Auth", description = "판매자 가입 신청 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthController {

	private final AuthSessionFacade authSessionFacade;

	@Operation(summary = "판매자(대표자) 가입 신청",
		description = "사업체(승인 대기)와 대표자 계정을 함께 만든다. 관리자가 승인하기 전에는 로그인할 수 없다. "
			+ "로그인 이메일의 인증(/auth/sellers/email-verification)을 마쳐야 한다. 인증하지 않았거나 유효 시간(20분)이 지났으면 400(AUTH307)이다.")
	@PostMapping("/auth/sellers/signup")
	public ResponseEntity<ApiResponse<AuthResponse.SellerSignup>> sellerSignup(
		@Valid @RequestBody AuthRequest.SellerSignup request
	) {
		AuthResponse.SellerSignup signup = authSessionFacade.sellerSignup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(signup));
	}
}
