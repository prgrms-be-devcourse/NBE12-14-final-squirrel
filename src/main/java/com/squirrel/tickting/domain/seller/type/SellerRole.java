package com.squirrel.tickting.domain.seller.type;

/** 판매자 계정의 역할. 토큰의 role 값(이름)과 권한(ROLE_SELLER_OWNER 등)이 이 이름을 따른다. */
public enum SellerRole {
	/** 대표자. 사업체당 1명 */
	OWNER,
	/** 담당자. 대표가 초대해서 들어온다 */
	STAFF
}
