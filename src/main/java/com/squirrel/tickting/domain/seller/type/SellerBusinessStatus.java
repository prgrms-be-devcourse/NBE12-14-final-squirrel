package com.squirrel.tickting.domain.seller.type;

/** 판매자 사업체 상태. APPROVED 인 사업체의 계정만 로그인할 수 있다. */
public enum SellerBusinessStatus {
	/** 승인 대기 */
	PENDING,
	/** 승인 */
	APPROVED,
	/** 반려 (사유가 남고 다시 신청할 수 있음) */
	REJECTED,
	/** 이용 정지 */
	SUSPENDED
}
