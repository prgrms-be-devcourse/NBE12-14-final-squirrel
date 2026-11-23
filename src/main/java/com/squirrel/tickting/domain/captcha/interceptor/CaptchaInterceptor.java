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
 * "사람이 보낸 요청인가"를 확인하는 인터셉터를 만들어 준다. 확인할 화면 이름(action)마다 인터셉터를 따로 만들어 경로에 건다.
 * 컨트롤러 코드를 고치지 않고 바깥에서 거는 방식이라 대상 컨트롤러는 캡챠를 모른다. 대상 경로와 action 은 WebMvcConfig 에서 정한다.
 *
 * <p>대기열 줄 서기(QUEUE_ENTER_ACTION)와 가입 전 이메일 인증번호 발송(EMAIL_VERIFY_SEND_ACTION)에 건다.
 * <ul>
 *   <li>대기열: 좌석 화면(구역 요약·구역 좌석 조회·점유)은 입장한 사람만 쓸 수 있어서, 여기서 막으면 봇은 좌석 화면에 들어오지 못한다.
 *       줄 서기(POST)만 확인한다. 상태 확인(GET)은 2~3초마다 불려서 매번 토큰을 받을 수 없고, 나가기(DELETE)는 막을 이유가 없다.
 *       로그인·계정 종류는 시큐리티가 먼저 확인하므로 여기 오는 요청은 로그인한 예매자다.</li>
 *   <li>이메일 인증번호 발송: 로그인 없이 아무 이메일로나 메일을 보낼 수 있는 공개 API 라서 자동화된 대량 발송을 막으려고 건다.
 *       이메일별 한도만으로는 이메일을 바꿔 가며 보내는 것을 막지 못한다. 확인(confirm)은 틀린 횟수 제한이 있어 걸지 않는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CaptchaInterceptor {

	public static final String TOKEN_HEADER = "X-Captcha-Token";
	/** 프론트가 토큰을 받을 때 넘기는 action 과 같아야 한다 */
	public static final String QUEUE_ENTER_ACTION = "queue_enter";
	public static final String EMAIL_VERIFY_SEND_ACTION = "email_verify_send";

	private final CaptchaVerifier captchaVerifier;

	/** 이 action 으로 받은 토큰만 통과시키는 인터셉터. POST 가 아닌 요청은 확인하지 않고 통과시킨다. */
	public HandlerInterceptor forAction(String action) {
		return new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
				if (!HttpMethod.POST.matches(request.getMethod())) {
					return true;
				}
				if (!captchaVerifier.verify(request.getHeader(TOKEN_HEADER), action)) {
					throw new BusinessException(CaptchaExceptionCode.CAPTCHA_FAILED);
				}
				return true;
			}
		};
	}
}
