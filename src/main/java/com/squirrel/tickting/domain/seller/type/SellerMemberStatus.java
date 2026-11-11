package com.squirrel.tickting.domain.seller.type;

/** 판매자 계정 상태. 담당자는 삭제하지 않고 비활성화만 한다. (과거 작업 이력의 작업자를 남기려고) */
public enum SellerMemberStatus {
	ACTIVE,
	INACTIVE
}
