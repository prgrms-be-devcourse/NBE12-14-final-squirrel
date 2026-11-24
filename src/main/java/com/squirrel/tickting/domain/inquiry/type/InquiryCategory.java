package com.squirrel.tickting.domain.inquiry.type;

import com.squirrel.tickting.global.security.AccountType;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.Getter;

/**
 * 문의 유형. 예매자와 판매자가 한 테이블을 쓰지만 고를 수 있는 유형은 다르다. (값은 임시, doc/inquiry-design.md)
 * 화면은 유형 목록 API 로 내 계정이 고를 수 있는 것만 받고, 다른 유형을 보내면 작성할 때 서버가 막는다.
 */
@Getter
public enum InquiryCategory {

	BOOKING("예매", AccountType.USER),
	CANCEL_REFUND("취소·환불", AccountType.USER),
	PAYMENT("결제", AccountType.USER),
	DELIVERY("티켓 배송·수령", AccountType.USER),
	ACCOUNT("회원·계정", AccountType.USER),
	PERFORMANCE("공연 등록·수정", AccountType.SELLER),
	SETTLEMENT("정산", AccountType.SELLER),
	BUSINESS("사업체·계정", AccountType.SELLER),
	ETC("기타", AccountType.USER, AccountType.SELLER);

	private final String label;
	private final Set<AccountType> allowedAccountTypes;

	InquiryCategory(String label, AccountType... allowedAccountTypes) {
		this.label = label;
		this.allowedAccountTypes = Set.of(allowedAccountTypes);
	}

	public boolean isAllowedFor(AccountType accountType) {
		return allowedAccountTypes.contains(accountType);
	}

	/** 그 계정이 고를 수 있는 유형. 선언한 순서대로다. */
	public static List<InquiryCategory> allowedFor(AccountType accountType) {
		return Arrays.stream(values()).filter(category -> category.isAllowedFor(accountType)).toList();
	}
}
