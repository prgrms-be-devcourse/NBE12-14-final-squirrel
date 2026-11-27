package com.squirrel.tickting.global.config;

import com.squirrel.tickting.domain.captcha.interceptor.CaptchaInterceptor;
import com.squirrel.tickting.domain.queue.interceptor.QueueAdmissionInterceptor;
import com.squirrel.tickting.domain.seller.interceptor.SellerAccountStatusInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC 인터셉터를 등록한다. 인터셉터가 하는 일은 각 도메인에 있고, 여기서는 어느 경로에 걸지만 정한다. */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

	private final CaptchaInterceptor captchaInterceptor;
	private final QueueAdmissionInterceptor queueAdmissionInterceptor;
	private final SellerAccountStatusInterceptor sellerAccountStatusInterceptor;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// 대기열 줄 서기 앞에서 캡챠를 확인한다. 같은 경로의 상태 확인·나가기(GET·DELETE)는 인터셉터 안에서 그냥 통과시킨다
		registry.addInterceptor(captchaInterceptor.forAction(CaptchaInterceptor.QUEUE_ENTER_ACTION))
			.addPathPatterns("/api/v1/schedules/*/queue");
		// 가입 전 이메일 인증번호 발송(예매자·판매자) 앞에서 캡챠를 확인한다. 확인(/confirm)은 경로가 달라 걸리지 않는다
		registry.addInterceptor(captchaInterceptor.forAction(CaptchaInterceptor.EMAIL_VERIFY_SEND_ACTION))
			.addPathPatterns("/api/v1/auth/users/email-verification", "/api/v1/auth/sellers/email-verification");
		// 대기열에 입장한 사람만 쓸 수 있는 API: 구역 요약, 구역 좌석 조회, 좌석 점유
		registry.addInterceptor(queueAdmissionInterceptor).addPathPatterns(
			"/api/v1/schedules/*/zones",
			"/api/v1/schedules/*/zones/*/seats",
			"/api/v1/schedules/*/bookings");
		// 비활성화된 판매자 계정의 남은 세션을 막는다. 로그인·로그아웃 같은 인증 API 는 세션 상태와 상관없이 부를 수 있게 뺀다
		registry.addInterceptor(sellerAccountStatusInterceptor)
			.addPathPatterns("/api/**")
			.excludePathPatterns("/api/v1/auth/**", "/api/v2/auth/**");
	}
}
