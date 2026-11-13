package com.squirrel.tickting.domain.user.controller;

import com.squirrel.tickting.domain.user.dto.UserRequest;
import com.squirrel.tickting.domain.user.dto.UserResponse;
import com.squirrel.tickting.domain.user.service.UserMemberService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
}
