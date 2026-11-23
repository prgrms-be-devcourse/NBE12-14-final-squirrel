package com.squirrel.tickting.domain.captcha.verifier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 캡챠 Mock. 구글 키 없이 대기열 입장 흐름을 개발·테스트하려고 둔다. 구글을 부르지 않으므로 부하 테스트도 이것으로 돌린다.
 *
 * 토큰으로 결과를 고른다.
 *   없음·빈 값   실패 (토큰을 안 붙인 요청)
 *   fail        실패 (점수가 낮거나 틀린 토큰)
 *   그 밖의 값   통과
 *
 * 토큰만 있으면 누구나 통과하므로 운영에서는 절대 쓰면 안 된다. (prod 프로필에서는 등록되지 않는다)
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(prefix = "app.captcha", name = "verifier", havingValue = "mock")
public class MockCaptchaVerifier implements CaptchaVerifier {

	static final String FAIL_TOKEN = "fail";

	@Override
	public boolean verify(String token, String action) {
		return token != null && !token.isBlank() && !FAIL_TOKEN.equals(token);
	}
}
