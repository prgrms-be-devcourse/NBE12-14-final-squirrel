package com.squirrel.tickting.domain.auth.controller;

import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.auth.service.AuthService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.global.util.CookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "가입·로그인·토큰 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@Operation(summary = "예매자 회원가입",
		description = "가입하면 바로 로그인된다. 액세스 토큰은 본문, 리프레시 토큰은 HttpOnly 쿠키로 내려간다.")
	@PostMapping("/auth/users/signup")
	public ResponseEntity<ApiResponse<AuthResponse.Token>> userSignup(
		@Valid @RequestBody AuthRequest.UserSignup request,
		HttpServletResponse response
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(authService.userSignup(request, response)));
	}

	@Operation(summary = "예매자 로그인")
	@PostMapping("/auth/users/login")
	public ResponseEntity<ApiResponse<AuthResponse.Token>> userLogin(
		@Valid @RequestBody AuthRequest.Login request,
		HttpServletResponse response
	) {
		return ResponseEntity.ok(ApiResponse.success(authService.userLogin(request, response)));
	}

	@Operation(summary = "판매자(대표자) 가입 신청",
		description = "사업체(승인 대기)와 대표자 계정을 함께 만든다. 관리자가 승인하기 전에는 로그인할 수 없다.")
	@PostMapping("/auth/sellers/signup")
	public ResponseEntity<ApiResponse<AuthResponse.SellerSignup>> sellerSignup(
		@Valid @RequestBody AuthRequest.SellerSignup request
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(authService.sellerSignup(request)));
	}

	@Operation(summary = "판매자 로그인", description = "사업체가 승인된 계정만 로그인할 수 있다.")
	@PostMapping("/auth/sellers/login")
	public ResponseEntity<ApiResponse<AuthResponse.Token>> sellerLogin(
		@Valid @RequestBody AuthRequest.Login request,
		HttpServletResponse response
	) {
		return ResponseEntity.ok(ApiResponse.success(authService.sellerLogin(request, response)));
	}

	@Operation(summary = "토큰 재발급", description = "쿠키의 리프레시 토큰으로 액세스 토큰을 다시 받는다. 리프레시 토큰도 새로 발급된다.")
	@PostMapping("/auth/refresh")
	public ResponseEntity<ApiResponse<AuthResponse.Token>> refresh(
		@CookieValue(name = CookieUtil.REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
		HttpServletResponse response
	) {
		return ResponseEntity.ok(ApiResponse.success(authService.refresh(refreshToken, response)));
	}

	@Operation(summary = "내 계정 정보", description = "액세스 토큰에 담긴 계정 종류·역할·사업체 번호를 돌려준다.")
	@GetMapping("/me")
	public ResponseEntity<ApiResponse<AuthResponse.Me>> me(@AuthenticationPrincipal CustomUserDetails account) {
		return ResponseEntity.ok(ApiResponse.success(AuthResponse.Me.from(account)));
	}
}
