package com.squirrel.tickting.domain.seller.controller;

import com.squirrel.tickting.domain.seller.dto.SellerInvitationRequest;
import com.squirrel.tickting.domain.seller.dto.SellerInvitationResponse;
import com.squirrel.tickting.domain.seller.facade.SellerInvitationFacade;
import com.squirrel.tickting.domain.seller.service.SellerInvitationService;
import com.squirrel.tickting.global.response.ApiResponse;
import com.squirrel.tickting.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 담당자 초대. 보내기는 판매자 대표만 쓰는 /api/v1/seller/** 경로이고, 링크 확인·가입은 로그인 전에 부르는 /api/v1/auth/** 경로다.
 * 두 경로 모두 SecurityConfig 의 규칙(판매자 / permitAll)에 이미 들어 있다.
 * 링크 확인·가입은 초대(seller 도메인)만 다뤄서 파사드 없이 서비스를 바로 부른다.
 */
@Tag(name = "SellerInvitation", description = "담당자 초대 API")
@RestController
@RequiredArgsConstructor
public class SellerInvitationController {

	private final SellerInvitationFacade sellerInvitationFacade;
	private final SellerInvitationService sellerInvitationService;

	@Operation(summary = "담당자 초대 (대표)",
		description = "담당자 이메일로 초대 링크(72시간)를 보낸다. 발송을 접수하면 바로 응답하며 200 은 메일이 도착했다는 뜻이 아니다. "
			+ "같은 이메일로 다시 초대하면 이전 링크는 무효가 된다. 대표가 아니면 403(AUTH100), 이미 판매자 계정이 있는 이메일이면 409(SELLER000), "
			+ "발송 대기가 가득 차면 503(AUTH304)이다.")
	@PreAuthorize("hasRole('SELLER_OWNER')")
	@PostMapping("/api/v1/seller/invitations")
	public ResponseEntity<ApiResponse<SellerInvitationResponse.Invited>> invite(
		@AuthenticationPrincipal CustomUserDetails account,
		@Valid @RequestBody SellerInvitationRequest.Invite request
	) {
		SellerInvitationResponse.Invited invited = sellerInvitationFacade.invite(account.getBusinessId(), account.getId(),
			request);
		return ResponseEntity.ok(ApiResponse.success(invited));
	}

	@Operation(summary = "초대 링크 확인",
		description = "링크가 유효한지와 가입할 이메일·회사 이름을 알려 주고 토큰을 소모하지 않는다. 만료·사용됨·다시 초대해서 무효·없는 토큰은 모두 200 + valid=false 이다.")
	@PostMapping("/api/v1/auth/sellers/invitations/validate")
	public ResponseEntity<ApiResponse<SellerInvitationResponse.Validity>> validate(
		@Valid @RequestBody SellerInvitationRequest.Validate request
	) {
		return ResponseEntity.ok(ApiResponse.success(sellerInvitationService.validate(request)));
	}

	@Operation(summary = "담당자 가입",
		description = "초대 링크의 토큰으로 초대한 회사의 담당자 계정을 만든다. 이메일은 초대받은 이메일 그대로다. 자동 로그인은 하지 않는다. "
			+ "같은 링크로 동시에 요청해도 한 번만 성공한다. 비밀번호 확인이 다르면 400(AUTH005), 링크가 없거나 만료·사용됨·무효면 400(SELLER008), "
			+ "그 사이 같은 이메일의 판매자 계정이 생겼으면 409(SELLER000)이고 이때 링크는 소모되지 않는다.")
	@PostMapping("/api/v1/auth/sellers/invitations/accept")
	public ResponseEntity<ApiResponse<SellerInvitationResponse.Accepted>> accept(
		@Valid @RequestBody SellerInvitationRequest.Accept request
	) {
		return ResponseEntity.ok(ApiResponse.success(sellerInvitationService.accept(request)));
	}
}
