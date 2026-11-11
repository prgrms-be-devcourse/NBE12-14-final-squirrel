package com.squirrel.tickting.domain.auth.identity;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 본인인증 Mock. 실제 인증 기관 없이 가입 흐름을 만들고 테스트하려고 둔다.
 *
 * 토큰 형식:  mock|이름|CI|휴대폰|생년월일(yyyyMMdd)
 * 예:         mock|홍길동|ci-hong-001|01012345678|19900101
 *
 * 토큰 안의 값을 그대로 믿으므로 운영에서는 절대 쓰면 안 된다. (prod 프로필에서는 등록되지 않는다)
 */
@Component
@Profile("!prod")
public class MockIdentityVerifier implements IdentityVerifier {

	private static final String PREFIX = "mock|";
	private static final int PART_COUNT = 5;

	@Override
	public VerifiedIdentity verify(String identityToken) {
		if (identityToken == null || !identityToken.startsWith(PREFIX)) {
			throw failed();
		}
		String[] parts = identityToken.split("\\|", -1);
		if (parts.length != PART_COUNT) {
			throw failed();
		}

		String name = parts[1].trim();
		String ci = parts[2].trim();
		String phone = parts[3].trim();
		if (name.isEmpty() || ci.isEmpty() || !phone.matches("^01[016789]\\d{7,8}$")) {
			throw failed();
		}

		try {
			LocalDate birthDate = LocalDate.parse(parts[4].trim(), DateTimeFormatter.BASIC_ISO_DATE);
			return new VerifiedIdentity(name, phone, birthDate, ci);
		} catch (DateTimeParseException e) {
			throw failed();
		}
	}

	private BusinessException failed() {
		return new BusinessException(AuthExceptionCode.IDENTITY_VERIFICATION_FAILED);
	}
}
