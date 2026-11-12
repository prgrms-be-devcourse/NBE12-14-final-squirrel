package com.squirrel.tickting.domain.seller.service;

import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.infra.repository.SellerBusinessRepository;
import com.squirrel.tickting.domain.seller.type.SellerBusinessSearchStatus;
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

	/**
	 * 사업체 검색. 검색어가 비어 있으면 상태 조건만 적용한다.
	 * 검색어는 LikeUtil 로 LIKE 패턴으로 바꿔서 넘긴다. (%, _ 를 글자 그대로 찾고, 비면 전체가 일치하는 패턴이 된다)
	 */
	@Transactional(readOnly = true)
	public Page<SellerBusiness> search(SellerBusinessSearchStatus status, String keyword, Pageable pageable) {
		return sellerBusinessRepository.search(status.toStatuses(), LikeUtil.containsPattern(keyword), pageable);
	}
}
