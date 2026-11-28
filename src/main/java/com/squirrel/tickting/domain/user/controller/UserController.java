package com.squirrel.tickting.domain.user.controller;

import com.squirrel.tickting.domain.auth.dto.AuthRequest;
import com.squirrel.tickting.domain.auth.facade.PasswordChangeFacade;
import com.squirrel.tickting.domain.user.dto.UserRequest;
import com.squirrel.tickting.domain.user.dto.UserResponse;
import com.squirrel.tickting.domain.user.facade.UserWithdrawFacade;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "User", description = "예매자 내 정보 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

	private final UserMemberService userMemberService;
	private final UserWithdrawFacade userWithdrawFacade;
	private final PasswordChangeFacade passwordChangeFacade;

	/** 회원 번호를 경로로 받지 않고 토큰에서 꺼낸다. 경로로 받으면 남의 번호를 넣어 볼 수 있기 때문이다. */
	@Operation(summary = "내 정보 조회", description = "로그인한 예매자 본인의 정보를 준다. 판매자·관리자 토큰은 403 이다.")
	@GetMapping("/me")
	public ResponseEntity<ApiResponse<UserResponse.Me>> getMe(
		@AuthenticationPrincipal CustomUserDetails account
	) {
		UserResponse.Me me = UserResponse.Me.from(userMemberService.getMe(account.getId()));
		return ResponseEntity.ok(ApiResponse.success(me));
	}

	@Operation(summary = "내 정보 수정",
		description = "닉네임·프로필 이미지를 통째로 바꾼다. 닉네임은 필수이고, profileImg 를 생략하거나 null 로 보내면 사진이 지워진다.")
	@PatchMapping("/me")
	public ResponseEntity<ApiResponse<UserResponse.UpdatedMe>> updateMe(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody UserRequest.UpdateMe request
	) {
		UserResponse.UpdatedMe updated = UserResponse.UpdatedMe.from(
			userMemberService.updateMe(account.getId(), request.nickname(), request.profileImg()));
		return ResponseEntity.ok(ApiResponse.success(updated));
	}

	@Operation(summary = "비밀번호 변경",
		description = "현재 비밀번호를 확인하고 새 비밀번호로 바꾼다. 지금 로그인은 그대로다. 현재 비밀번호가 틀리면 400 AUTH311 이고, "
			+ "로그인 실패와 합쳐 여러 번 틀리면 429 AUTH004 로 로그인과 변경이 잠시 막힌다. 새 비밀번호가 현재와 같으면 400 AUTH312 다.")
	@PatchMapping("/me/password")
	public ResponseEntity<ApiResponse<Void>> changePassword(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody AuthRequest.PasswordChange request,
		HttpServletRequest httpRequest
	) {
		passwordChangeFacade.changeForUser(account.getId(), request, httpRequest.getRemoteAddr());
		return ResponseEntity.ok(ApiResponse.noContentSuccess());
	}

	@Operation(summary = "회원 탈퇴",
		description = "개인정보를 지우고 탈퇴 처리한 뒤 지금 로그인(세션)을 끝낸다. 결제 대기 중이거나 아직 시작하지 않은 공연의 "
			+ "결제 완료 예매가 있으면 409 USER003 이다. (예매를 취소하거나 공연이 시작된 뒤에 탈퇴할 수 있다)")
	@DeleteMapping("/me")
	public ResponseEntity<ApiResponse<Void>> withdraw(
		@AuthenticationPrincipal CustomUserDetails account,
		HttpServletRequest httpRequest,
		HttpServletResponse httpResponse
	) {
		userWithdrawFacade.withdraw(account.getId(), httpRequest, httpResponse);
		return ResponseEntity.ok(ApiResponse.noContentSuccess());
	}
}
