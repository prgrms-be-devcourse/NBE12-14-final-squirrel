package com.squirrel.tickting.domain.seller.type;

import java.util.EnumSet;
import java.util.Set;

/**
 * 사업체 목록의 상태 검색 조건. 사업체 상태에 "전체"를 더한 값이다.
 * 상태가 아니라 "조건"이라서 SellerBusinessStatus 와 따로 둔다. (전체는 저장되는 상태가 아니다)
 */
public enum SellerBusinessSearchStatus {
	/** 모든 상태 */
	ALL,
	PENDING,
	APPROVED,
	REJECTED,
	SUSPENDED;

	/** 검색에 쓸 사업체 상태 목록. 조건이 하나도 비지 않게 "전체"는 모든 상태로 펼친다. */
	public Set<SellerBusinessStatus> toStatuses() {
		if (this == ALL) {
			return EnumSet.allOf(SellerBusinessStatus.class);
		}
		return EnumSet.of(SellerBusinessStatus.valueOf(name()));
	}
}
