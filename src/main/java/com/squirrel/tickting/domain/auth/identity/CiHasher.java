package com.squirrel.tickting.domain.auth.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** CI 원문은 저장하지 않고 SHA-256 해시(16진수 64자)만 저장한다. 같은 사람은 항상 같은 해시가 나온다. */
public final class CiHasher {

	private CiHasher() {
	}

	public static String hash(String ci) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(ci.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			// 모든 JVM 이 SHA-256 을 지원하므로 도달하지 않는다.
			throw new IllegalStateException(e);
		}
	}
}
