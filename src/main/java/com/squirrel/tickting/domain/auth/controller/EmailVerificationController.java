package com.squirrel.tickting.domain.auth.controller;

import com.squirrel.tickting.domain.auth.dto.EmailVerificationRequest;
import com.squirrel.tickting.domain.auth.dto.EmailVerificationResponse;
import com.squirrel.tickting.domain.auth.facade.EmailVerificationFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예매자의 이메일 인증. 경로가 /users/** 라서 SecurityConfig 의 USER_ONLY_PATHS 에 이미 들어 있다. (예매자만)
 * 이메일 주소와 회원 번호는 요청으로 받지 않고 토큰으로 본인을 정해, 가입한 이메일로만 보낸다.
 */
@Tag(name = "EmailVerification", description = "예매자 이메일 인증 API")
@RestController
@RequestMapping("/api/v1/users/me/email-verification")
@RequiredArgsConstructor
public class EmailVerificationController {

	private final EmailVerificationFacade emailVerificationFacade;

	@Operation(summary = "이메일 인증번호 발송",
		description = "가입한 이메일로 인증번호(숫자 6자리)를 보낸다. 발송을 접수하면 바로 응답하며 200 은 메일이 도착했다는 뜻이 아니다. "
			+ "재발송하면 이전 인증번호는 바로 무효다. 이미 인증했으면 409(AUTH301), 60초 안이면 429(AUTH302), 하루 10회를 넘으면 429(AUTH303), "
			+ "발송 대기가 가득 차면 503(AUTH304)이다.")
	@PostMapping
	public ResponseEntity<ApiResponse<EmailVerificationResponse.Sent>> send(
		@AuthenticationPrincipal CustomUserDetails account
	) {
		EmailVerificationResponse.Sent sent = emailVerificationFacade.send(account.getId());
		return ResponseEntity.ok(ApiResponse.success(sent));
	}

	@Operation(summary = "이메일 인증번호 확인",
		description = "받은 인증번호가 맞으면 이메일 인증을 마친 것으로 기록한다. 다시 로그인하지 않아도 바로 예매할 수 있다. "
			+ "틀리면 400(AUTH305, 남은 횟수 포함), 5번 틀리거나 만료됐거나 받은 적이 없으면 400(AUTH306)이라 다시 받아야 한다.")
	@PostMapping("/confirm")
	public ResponseEntity<ApiResponse<EmailVerificationResponse.Confirmed>> confirm(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody EmailVerificationRequest.Confirm request
	) {
		EmailVerificationResponse.Confirmed confirmed = emailVerificationFacade.confirm(account.getId(), request);
		return ResponseEntity.ok(ApiResponse.success(confirmed));
	}
}
