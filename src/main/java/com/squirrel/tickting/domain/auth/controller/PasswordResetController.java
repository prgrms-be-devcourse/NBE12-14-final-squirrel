package com.squirrel.tickting.domain.auth.controller;

import com.squirrel.tickting.domain.auth.dto.PasswordResetRequest;
import com.squirrel.tickting.domain.auth.dto.PasswordResetResponse;
import com.squirrel.tickting.domain.auth.facade.PasswordResetFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 비밀번호 찾기. 로그아웃 상태에서 부르는 공개 API 다. (경로가 /api/v1/auth/** 라서 SecurityConfig 의 permitAll 에 이미 들어 있다)
 * 메일 요청만 계정 종류별 경로이고, 링크 확인·변경은 토큰이 계정 종류를 알고 있어서 공통 경로다.
 */
@Tag(name = "PasswordReset", description = "비밀번호 찾기 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class PasswordResetController {

	private final PasswordResetFacade passwordResetFacade;

	@Operation(summary = "예매자 비밀번호 재설정 메일 요청",
		description = "가입한 이메일로 재설정 링크(15분)를 보낸다. 발송을 접수하면 바로 응답하며 200 은 메일이 도착했다는 뜻이 아니다. "
			+ "가입되지 않은 이메일이면 404(AUTH308), 60초 안이면 429(AUTH302), 하루 10회를 넘으면 429(AUTH303), 발송 대기가 가득 차면 503(AUTH304)이다.")
	@PostMapping("/users/password-reset/request")
	public ResponseEntity<ApiResponse<PasswordResetResponse.Sent>> requestForUser(
		@Valid @RequestBody PasswordResetRequest.Mail request
	) {
		PasswordResetResponse.Sent sent = passwordResetFacade.requestForUser(request);
		return ResponseEntity.ok(ApiResponse.success(sent));
	}

	@Operation(summary = "판매자 비밀번호 재설정 메일 요청",
		description = "대표·담당자 모두 대상이다. 계정·사업체 상태와 무관하게 요청할 수 있다. 에러는 예매자와 같다.")
	@PostMapping("/sellers/password-reset/request")
	public ResponseEntity<ApiResponse<PasswordResetResponse.Sent>> requestForSeller(
		@Valid @RequestBody PasswordResetRequest.Mail request
	) {
		PasswordResetResponse.Sent sent = passwordResetFacade.requestForSeller(request);
		return ResponseEntity.ok(ApiResponse.success(sent));
	}

	@Operation(summary = "재설정 링크 확인",
		description = "링크가 유효한지만 알려 주고 토큰을 소모하지 않는다. 만료·사용됨·없는 토큰은 모두 200 + valid=false 이다.")
	@PostMapping("/password-reset/validate")
	public ResponseEntity<ApiResponse<PasswordResetResponse.Validity>> validate(
		@Valid @RequestBody PasswordResetRequest.Validate request
	) {
		PasswordResetResponse.Validity validity = passwordResetFacade.validate(request);
		return ResponseEntity.ok(ApiResponse.success(validity));
	}

	@Operation(summary = "비밀번호 재설정",
		description = "링크의 토큰과 새 비밀번호로 비밀번호를 바꾼다. 같은 링크로 동시에 요청해도 한 번만 성공한다. 성공하면 그 계정의 다른 재설정 링크가 모두 폐기되고 "
			+ "변경 알림 메일이 간다. 자동 로그인은 하지 않는다. 비밀번호 확인이 다르면 400(AUTH005), 링크가 만료·사용됨이면 400(AUTH309)이다.")
	@PostMapping("/password-reset/confirm")
	public ResponseEntity<ApiResponse<Void>> confirm(
		@Valid @RequestBody PasswordResetRequest.Confirm request
	) {
		passwordResetFacade.confirm(request);
		return ResponseEntity.ok(ApiResponse.noContentSuccess());
	}
}
