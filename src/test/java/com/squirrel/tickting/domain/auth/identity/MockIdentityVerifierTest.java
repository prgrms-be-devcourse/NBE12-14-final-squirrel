package com.squirrel.tickting.domain.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockIdentityVerifierTest {

	private final MockIdentityVerifier verifier = new MockIdentityVerifier();

	@Test
	@DisplayName("형식에 맞는 토큰이면 이름, 휴대폰, 생년월일, CI 를 꺼낸다")
	void verifyValidToken() {
		VerifiedIdentity identity = verifier.verify("mock|홍길동|ci-001|01012345678|19900101");

		assertThat(identity.name()).isEqualTo("홍길동");
		assertThat(identity.ci()).isEqualTo("ci-001");
		assertThat(identity.phone()).isEqualTo("01012345678");
		assertThat(identity.birthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
	}

	@Test
	@DisplayName("형식이 틀리거나 값이 비었거나 없는 날짜이면 AUTH300 으로 거절한다")
	void rejectInvalidTokens() {
		for (String token : new String[] {
			null, "", "garbage", "real|홍길동|ci|01012345678|19900101", "mock|홍길동|ci|01012345678",
			"mock|홍길동|ci|01012345678|19900101|extra", "mock||ci|01012345678|19900101",
			"mock|홍길동||01012345678|19900101", "mock|홍길동|ci|0212345678|19900101",
			"mock|홍길동|ci|01012345678|19901301", "mock|홍길동|ci|01012345678|abc"}) {
			assertThatThrownBy(() -> verifier.verify(token))
				.as("token=%s", token)
				.isInstanceOfSatisfying(BusinessException.class, e ->
					assertThat(e.getExceptionCode()).isEqualTo(AuthExceptionCode.IDENTITY_VERIFICATION_FAILED));
		}
	}

	@Test
	@DisplayName("같은 CI 는 항상 같은 해시가 나오고, 다른 CI 는 다른 해시가 나온다")
	void ciHashIsStable() {
		assertThat(CiHasher.hash("ci-001")).isEqualTo(CiHasher.hash("ci-001")).hasSize(64);
		assertThat(CiHasher.hash("ci-001")).isNotEqualTo(CiHasher.hash("ci-002"));
	}
}
