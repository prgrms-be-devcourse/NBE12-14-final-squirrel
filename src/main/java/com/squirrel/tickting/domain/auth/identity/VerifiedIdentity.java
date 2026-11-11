package com.squirrel.tickting.domain.auth.identity;

import java.time.LocalDate;

/**
 * 본인인증으로 확인된 정보.
 * ci 는 인증 기관이 사람마다 주는 고유값(원문)이라 저장하지 않고, 해시(CiHasher)해서 쓴다.
 */
public record VerifiedIdentity(
	String name,
	String phone,
	LocalDate birthDate,
	String ci
) {
}
