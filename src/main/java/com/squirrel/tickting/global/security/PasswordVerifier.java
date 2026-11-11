package com.squirrel.tickting.global.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 로그인 때 비밀번호를 비교한다.
 *
 * 없는 이메일이라고 비교를 건너뛰면 응답이 빨라서, 응답 시간으로 가입 여부를 알아낼 수 있다.
 * 그래서 계정이 없을 때도 가짜 해시와 한 번 비교해서 걸리는 시간을 맞춘다.
 */
@Component
@RequiredArgsConstructor
public class PasswordVerifier {

	private final PasswordEncoder passwordEncoder;

	private String dummyHash;

	@PostConstruct
	void init() {
		this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
	}

	/**
	 * @param storedHash 저장된 비밀번호 해시. 계정이 없으면 null 을 넘긴다. (이때는 항상 false)
	 */
	public boolean matches(String rawPassword, String storedHash) {
		boolean matches = passwordEncoder.matches(rawPassword, storedHash == null ? dummyHash : storedHash);
		return storedHash != null && matches;
	}
}
