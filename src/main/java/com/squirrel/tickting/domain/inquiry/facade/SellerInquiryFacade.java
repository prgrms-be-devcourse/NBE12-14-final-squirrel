package com.squirrel.tickting.domain.inquiry.facade;

import com.squirrel.tickting.domain.inquiry.dto.SellerInquiryRequest;
import com.squirrel.tickting.domain.inquiry.dto.UserInquiryResponse;
import com.squirrel.tickting.domain.inquiry.service.InquiryService;
import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자 문의 작성. 작성자 이름·이메일은 판매자 도메인에 있어서 서비스끼리 부르지 않고 이 파사드가 순서대로 부른다.
 * 목록·상세는 문의만 다뤄서 컨트롤러가 서비스를 바로 부른다.
 */
@Component
@RequiredArgsConstructor
public class SellerInquiryFacade {

	private final TransactionTemplate transactionTemplate;
	private final SellerMemberService sellerMemberService;
	private final InquiryService inquiryService;

	public UserInquiryResponse.Created create(Long memberId, Long businessId, SellerInquiryRequest.Create request) {
		return transactionTemplate.execute(status -> {
			SellerMember member = sellerMemberService.getMine(businessId, memberId);
			return inquiryService.createForSeller(memberId, businessId, member.getName(), member.getEmail(), request);
		});
	}
}
