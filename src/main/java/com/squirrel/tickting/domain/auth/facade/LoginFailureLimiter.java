package com.squirrel.tickting.domain.auth.facade;

import com.squirrel.tickting.domain.auth.service.LoginAttemptService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 제한을 계정 확인 앞뒤에 붙인다. v1(AuthFacade)과 v2(AuthSessionFacade)가 같이 쓴다.
 * 한쪽에만 붙이면 다른 버전의 로그인 API 로 제한을 피해 갈 수 있어서 한곳에 두었다.
 */
@Component
@RequiredArgsConstructor
class LoginFailureLimiter {

	private final LoginAttemptService loginAttemptService;

	/**
	 * 잠겨 있으면 비밀번호를 비교하지 않고 막는다(AUTH004).
	 * 비밀번호가 틀린 경우(AUTH001)만 센다. 비활성 계정·승인 대기처럼 비밀번호는 맞은 실패는 무차별 대입이 아니라서 세지 않는다.
	 */
	CustomUserDetails login(AccountType type, String email, String ip, Supplier<CustomUserDetails> verify) {
		loginAttemptService.checkNotLocked(type, email, ip);
		try {
			CustomUserDetails account = verify.get();
			loginAttemptService.reset(type, email, ip);
			return account;
		} catch (BusinessException e) {
			if (e.getExceptionCode() == AuthExceptionCode.INVALID_CREDENTIALS) {
				loginAttemptService.recordFailure(type, email, ip);
			}
			throw e;
		}
	}
}
