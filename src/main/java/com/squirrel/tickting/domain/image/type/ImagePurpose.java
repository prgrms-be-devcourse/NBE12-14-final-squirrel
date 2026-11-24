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
	PROFILE(AccountType.USER),
	/**
	 * 문의 첨부. 문의는 예매자·판매자가 함께 쓰지만 용도 하나에 계정 종류 하나라서 둘로 나눴다.
	 * 용도 하나가 여러 계정을 받게 바꾸면 기존 용도의 권한 확인까지 고쳐야 한다.
	 * 다른 용도처럼 public/ 아래라 주소를 아는 사람은 누구나 볼 수 있다. 개인정보가 담긴 사진은 올리지 않도록 화면이 안내한다.
	 */
	USER_INQUIRY(AccountType.USER),
	SELLER_INQUIRY(AccountType.SELLER);

	private final AccountType allowedAccountType;
}
