package com.squirrel.tickting.domain.auth.controller;

import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.dto.AuthResponse;
import com.squirrel.tickting.domain.auth.facade.AuthSessionFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.SessionAuthenticator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * v2 인증(Redis 세션). 로그인하면 SESSION 쿠키가 내려가고, 이후 업무 API 는 경로 변경 없이
 * 그 쿠키만 보내면 된다. (업무 API 는 JWT 와 세션을 모두 인정한다)
 * v1(JWT) 인증은 AuthController 에 그대로 있다.
 */
@Tag(name = "Auth v2 (세션)", description = "Redis 세션 방식의 가입·로그인·로그아웃 API")
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class AuthSessionController {

	private final AuthSessionFacade authSessionFacade;
	private final SessionAuthenticator sessionAuthenticator;

	@Operation(summary = "예매자 회원가입 (세션)", description = "가입하면 바로 로그인된다. SESSION 쿠키가 내려간다.")
	@PostMapping("/auth/users/signup")
	public ResponseEntity<ApiResponse<AuthResponse.Session>> userSignup(
		@Valid @RequestBody AuthRequest.UserSignup request,
		HttpServletRequest httpRequest,
		HttpServletResponse httpResponse
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(authSessionFacade.userSignup(request, httpRequest, httpResponse)));
	}

	@Operation(summary = "예매자 로그인 (세션)")
	@PostMapping("/auth/users/login")
	public ResponseEntity<ApiResponse<AuthResponse.Session>> userLogin(
		@Valid @RequestBody AuthRequest.Login request,
		HttpServletRequest httpRequest,
		HttpServletResponse httpResponse
	) {
		return ResponseEntity.ok(ApiResponse.success(authSessionFacade.userLogin(request, httpRequest, httpResponse)));
	}

	@Operation(summary = "판매자 로그인 (세션)", description = "사업체가 승인된 계정만 로그인할 수 있다. 판매자 가입 신청은 v1 의 API 를 쓴다.")
	@PostMapping("/auth/sellers/login")
	public ResponseEntity<ApiResponse<AuthResponse.Session>> sellerLogin(
		@Valid @RequestBody AuthRequest.Login request,
		HttpServletRequest httpRequest,
		HttpServletResponse httpResponse
	) {
		return ResponseEntity.ok(ApiResponse.success(authSessionFacade.sellerLogin(request, httpRequest, httpResponse)));
	}

	@Operation(summary = "관리자 로그인 (세션)", description = "관리자는 가입이 없다. 개발 때는 db/seed 의 시드 계정으로 로그인한다.")
	@PostMapping("/auth/admins/login")
	public ResponseEntity<ApiResponse<AuthResponse.Session>> adminLogin(
		@Valid @RequestBody AuthRequest.Login request,
		HttpServletRequest httpRequest,
		HttpServletResponse httpResponse
	) {
		return ResponseEntity.ok(ApiResponse.success(authSessionFacade.adminLogin(request, httpRequest, httpResponse)));
	}

	@Operation(summary = "로그아웃 (세션)",
		description = "세션을 Redis 에서 지운다. 같은 쿠키로는 더 이상 로그인되지 않는다. 로그인 상태가 아니어도 200 이다.")
	@PostMapping("/auth/logout")
	public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
		sessionAuthenticator.logout(httpRequest, httpResponse);
		return ResponseEntity.ok(ApiResponse.noContentSuccess());
	}
}
