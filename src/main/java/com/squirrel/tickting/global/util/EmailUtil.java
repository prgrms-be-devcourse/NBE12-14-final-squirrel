package com.squirrel.tickting.global.util;

import java.util.Locale;

public final class EmailUtil {

	private EmailUtil() {
	}

	/**
	 * 이메일을 저장·조회하기 전에 맞춘다. 앞뒤 공백을 없애고 소문자로 바꾼다.
	 * 안 하면 "A@x.com" 과 "a@x.com" 이 서로 다른 계정이 되어 중복 검사와 로그인이 어긋난다.
	 */
	public static String normalize(String email) {
		return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
	}
}
