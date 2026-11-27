package com.squirrel.tickting.domain.seller.controller;

import com.squirrel.tickting.domain.seller.dto.SellerInvitationRequest;
import com.squirrel.tickting.domain.seller.dto.SellerInvitationResponse;
import com.squirrel.tickting.domain.seller.facade.SellerInvitationFacade;
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
 * 담당자 초대. 보내기는 판매자 대표만 쓰는 /api/v1/seller/** 경로다. (SecurityConfig 의 판매자 규칙에 이미 들어 있다)
 */
@Tag(name = "SellerInvitation", description = "담당자 초대 API")
@RestController
@RequiredArgsConstructor
public class SellerInvitationController {

	private final SellerInvitationFacade sellerInvitationFacade;

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
}
