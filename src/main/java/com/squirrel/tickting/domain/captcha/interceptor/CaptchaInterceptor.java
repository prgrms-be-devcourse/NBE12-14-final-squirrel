package com.squirrel.tickting.domain.captcha.interceptor;

import com.squirrel.tickting.domain.captcha.verifier.CaptchaVerifier;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CaptchaExceptionCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 대기열 줄 서기 앞에서 "사람이 보낸 요청인가"를 확인한다.
 * 좌석 화면(구역 요약·좌석 조회·점유)은 입장한 사람만 쓸 수 있어서, 여기서 막으면 봇은 좌석 화면에 들어오지 못한다.
 * 대기열 코드를 고치지 않고 바깥에서 거는 방식이라 대기열은 캡챠를 모른다. 대상 경로는 WebMvcConfig 에서 정한다.
 *
 * <p>줄 서기(POST)만 확인한다. 상태 확인(GET)은 2~3초마다 불려서 매번 토큰을 받을 수 없고, 나가기(DELETE)는 막을 이유가 없다.
 * 로그인·계정 종류는 시큐리티가 먼저 확인하므로 여기 오는 요청은 로그인한 예매자다.
 */
@Component
@RequiredArgsConstructor
public class CaptchaInterceptor implements HandlerInterceptor {

	public static final String TOKEN_HEADER = "X-Captcha-Token";
	/** 프론트가 토큰을 받을 때 넘기는 action 과 같아야 한다 */
	public static final String QUEUE_ENTER_ACTION = "queue_enter";

	private final CaptchaVerifier captchaVerifier;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return true;
		}
		if (!captchaVerifier.verify(request.getHeader(TOKEN_HEADER), QUEUE_ENTER_ACTION)) {
			throw new BusinessException(CaptchaExceptionCode.CAPTCHA_FAILED);
		}
		return true;
	}
}
