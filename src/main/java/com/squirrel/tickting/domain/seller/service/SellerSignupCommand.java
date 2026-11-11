package com.squirrel.tickting.domain.seller.service;

import com.squirrel.tickting.domain.auth.identity.VerifiedIdentity;

/** 판매자 가입 신청에 필요한 값. 사업체 정보와 대표자 계정 정보, 본인인증 결과를 묶는다. */
public record SellerSignupCommand(
	String email,
	String password,
	VerifiedIdentity identity,
	String companyName,
	String representativeName,
	String businessNumber,
	String contactPhone,
	String contactEmail
) {
}
