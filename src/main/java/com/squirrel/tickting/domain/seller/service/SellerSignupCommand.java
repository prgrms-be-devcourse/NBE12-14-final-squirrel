package com.squirrel.tickting.domain.seller.service;

/** 판매자 가입 신청에 필요한 값. 사업체 정보와 대표자 계정 정보를 묶는다. */
public record SellerSignupCommand(
	String email,
	String password,
	String name,
	String phone,
	String companyName,
	String representativeName,
	String businessNumber,
	String contactPhone,
	String contactEmail
) {
}
