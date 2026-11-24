package com.squirrel.tickting.domain.image.type;

import com.squirrel.tickting.global.security.AccountType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 이미지를 올리는 용도. 그 용도로 올릴 수 있는 계정 종류가 정해져 있고, 소문자 이름이 저장 경로의 폴더가 된다. */
@Getter
@RequiredArgsConstructor
public enum ImagePurpose {

	POSTER(AccountType.SELLER),
	DETAIL(AccountType.SELLER),
	PROFILE(AccountType.USER);

	private final AccountType allowedAccountType;
}
