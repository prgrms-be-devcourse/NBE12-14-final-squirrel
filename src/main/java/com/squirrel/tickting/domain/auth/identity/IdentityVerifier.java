package com.squirrel.tickting.domain.auth.identity;

/**
 * 본인인증 결과 확인. 외부 연동 경계라서 인터페이스로 둔다.
 * 지금은 MockIdentityVerifier 를 쓰고, 실제 인증 연동(토스 등)을 붙일 때 구현체만 바꾼다.
 */
public interface IdentityVerifier {

	/**
	 * 프론트가 본인인증을 끝내고 받은 토큰으로 인증된 정보를 확인한다.
	 *
	 * @throws com.squirrel.tickting.global.exception.BusinessException 확인할 수 없는 토큰이면 AUTH300
	 */
	VerifiedIdentity verify(String identityToken);
}
