package com.squirrel.tickting.domain.auth.controller;

import com.squirrel.tickting.domain.auth.dto.EmailVerificationRequest;
import com.squirrel.tickting.domain.auth.dto.EmailVerificationResponse;
import com.squirrel.tickting.domain.auth.facade.EmailVerificationFacade;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.AccountType;
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
 * 가입 전 이메일 인증. 예매자(/users/...)와 판매자(/sellers/...)가 같은 흐름을 쓰고, 인증 상태는 계정 종류별로 따로 둔다.
 * 경로가 /auth/** 라서 SecurityConfig 에서 이미 공개다. (가입 전이라 로그인이 없다)
 * 인증을 마친 이메일로만 가입할 수 있다. 확인은 가입 API 가 한다.
 * 발송 경로의 캡챠는 WebMvcConfig 에서 건다. 새 경로를 만들면 거기에도 넣는다.
 */
@Tag(name = "EmailVerification", description = "가입 전 이메일 인증 API (예매자·판매자)")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class EmailVerificationController {

	private final EmailVerificationFacade emailVerificationFacade;

	// ---------------------------------------------------------------- 예매자

	@Operation(summary = "예매자 이메일 인증번호 발송",
		description = "입력한 이메일로 인증번호(숫자 6자리)를 보낸다. 발송을 접수하면 바로 응답하며 200 은 메일이 도착했다는 뜻이 아니다. "
			+ "재발송하면 이전 인증번호는 바로 무효다. 이미 가입한 이메일이면 409(USER000), 60초 안이면 429(AUTH302), "
			+ "하루 10회를 넘으면 429(AUTH303), 발송 대기가 가득 차면 503(AUTH304)이다.")
	@PostMapping("/users/email-verification")
	public ResponseEntity<ApiResponse<EmailVerificationResponse.Sent>> sendForUser(
		@Valid @RequestBody EmailVerificationRequest.Send request
	) {
		return send(AccountType.USER, request);
	}

	@Operation(summary = "예매자 이메일 인증번호 확인",
		description = "받은 인증번호가 맞으면 그 이메일을 인증한 것으로 기록한다. 응답의 verifiedUntil 까지 가입을 마쳐야 한다. "
			+ "틀리면 400(AUTH305, 남은 횟수 포함), 5번 틀리거나 만료됐거나 받은 적이 없으면 400(AUTH306)이라 다시 받아야 한다.")
	@PostMapping("/users/email-verification/confirm")
	public ResponseEntity<ApiResponse<EmailVerificationResponse.Confirmed>> confirmForUser(
		@Valid @RequestBody EmailVerificationRequest.Confirm request
	) {
		return confirm(AccountType.USER, request);
	}

	// ---------------------------------------------------------------- 판매자

	@Operation(summary = "판매자 이메일 인증번호 발송",
		description = "판매자 가입 신청 전에 로그인 이메일의 주인을 확인한다. 동작은 예매자와 같고, 인증 상태와 한도는 예매자와 따로 센다. "
			+ "이미 판매자로 가입한 이메일이면 409(SELLER000), 60초 안이면 429(AUTH302), "
			+ "하루 10회를 넘으면 429(AUTH303), 발송 대기가 가득 차면 503(AUTH304)이다. 캡챠 토큰(X-Captcha-Token)이 필요하다.")
	@PostMapping("/sellers/email-verification")
	public ResponseEntity<ApiResponse<EmailVerificationResponse.Sent>> sendForSeller(
		@Valid @RequestBody EmailVerificationRequest.Send request
	) {
		return send(AccountType.SELLER, request);
	}

	@Operation(summary = "판매자 이메일 인증번호 확인",
		description = "받은 인증번호가 맞으면 그 이메일을 판매자 가입용으로 인증한 것으로 기록한다. 응답의 verifiedUntil 까지 가입 신청을 마쳐야 한다. "
			+ "틀리면 400(AUTH305, 남은 횟수 포함), 5번 틀리거나 만료됐거나 받은 적이 없으면 400(AUTH306)이라 다시 받아야 한다.")
	@PostMapping("/sellers/email-verification/confirm")
	public ResponseEntity<ApiResponse<EmailVerificationResponse.Confirmed>> confirmForSeller(
		@Valid @RequestBody EmailVerificationRequest.Confirm request
	) {
		return confirm(AccountType.SELLER, request);
	}

	private ResponseEntity<ApiResponse<EmailVerificationResponse.Sent>> send(
		AccountType type, EmailVerificationRequest.Send request
	) {
		EmailVerificationResponse.Sent sent = emailVerificationFacade.send(type, request);
		return ResponseEntity.ok(ApiResponse.success(sent));
	}

	private ResponseEntity<ApiResponse<EmailVerificationResponse.Confirmed>> confirm(
		AccountType type, EmailVerificationRequest.Confirm request
	) {
		EmailVerificationResponse.Confirmed confirmed = emailVerificationFacade.confirm(type, request);
		return ResponseEntity.ok(ApiResponse.success(confirmed));
	}
}
