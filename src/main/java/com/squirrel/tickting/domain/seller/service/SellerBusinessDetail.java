package com.squirrel.tickting.domain.seller.service;

import com.squirrel.tickting.domain.seller.infra.entity.SellerBusiness;
import com.squirrel.tickting.domain.seller.infra.entity.SellerMember;

/**
 * 사업체 상세 조회 결과. 사업체와 대표 계정을 서비스 트랜잭션 안에서 함께 읽어 담는다.
 * (컨트롤러에서 지연 로딩 객체를 꺼내면 트랜잭션이 끝난 뒤라 LazyInitializationException 이 난다)
 */
public record SellerBusinessDetail(SellerBusiness business, SellerMember owner) {
}
