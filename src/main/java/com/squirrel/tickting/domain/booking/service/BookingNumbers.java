package com.squirrel.tickting.domain.booking.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 사용자에게 보이는 예매번호. 날짜(yyyyMMdd) + 영문 대문자·숫자 7자리다. (예: 20261109KCZB03R)
 * 순서대로 늘어나는 번호는 예매 건수를 짐작하게 하고 남의 번호를 추측하기 쉬워서 무작위로 만든다.
 * 겹치면 UNIQUE 인덱스(uq_booking_no)가 막는다. 겹칠 확률이 아주 낮아서 다시 만드는 코드는 두지 않는다.
 */
final class BookingNumbers {

	private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
	private static final int RANDOM_LENGTH = 7;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final SecureRandom RANDOM = new SecureRandom();

	private BookingNumbers() {
	}

	static String next(LocalDate today) {
		StringBuilder number = new StringBuilder(DATE.format(today));
		for (int i = 0; i < RANDOM_LENGTH; i++) {
			number.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
		}
		return number.toString();
	}
}
