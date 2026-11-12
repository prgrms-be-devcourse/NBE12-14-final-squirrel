package com.squirrel.tickting.domain.seller.service;

import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;
import com.squirrel.tickting.domain.seller.infra.repository.SellerBusinessRepository;
import com.squirrel.tickting.domain.seller.infra.repository.SellerMemberRepository;
import com.squirrel.tickting.domain.seller.type.SellerBusinessSearchStatus;
import com.squirrel.tickting.domain.seller.type.SellerRole;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.SellerExceptionCode;
import com.squirrel.tickting.global.util.LikeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 판매자 사업체만 다룬다. 승인·반려처럼 감사 로그가 얽히는 흐름은 파사드가 이어 준다. */
@Service
@RequiredArgsConstructor
public class SellerBusinessService {

	private final SellerBusinessRepository sellerBusinessRepository;
	private final SellerMemberRepository sellerMemberRepository;

	/**
	 * 사업체 검색. 검색어가 비어 있으면 상태 조건만 적용한다.
	 * 검색어는 LikeUtil 로 LIKE 패턴으로 바꿔서 넘긴다. (%, _ 를 글자 그대로 찾고, 비면 전체가 일치하는 패턴이 된다)
	 */
	@Transactional(readOnly = true)
	public Page<SellerBusiness> search(SellerBusinessSearchStatus status, String keyword, Pageable pageable) {
		return sellerBusinessRepository.search(status.toStatuses(), LikeUtil.containsPattern(keyword), pageable);
	}

	/** 사업체 상세. 신청과 동시에 만들어진 대표 계정을 같이 돌려준다. */
	@Transactional(readOnly = true)
	public SellerBusinessDetail getDetail(Long businessId) {
		SellerBusiness business = sellerBusinessRepository.findById(businessId)
			.orElseThrow(() -> new BusinessException(SellerExceptionCode.BUSINESS_NOT_FOUND));
		// 가입이 사업체와 대표를 한 트랜잭션으로 만들기 때문에 대표가 없을 수는 없지만, 없어도 상세는 볼 수 있게 null 을 허용한다.
		SellerMember owner = sellerMemberRepository.findByBusinessIdAndRole(businessId, SellerRole.OWNER)
			.orElse(null);
		return new SellerBusinessDetail(business, owner);
	}
}
