package com.squirrel.tickting.domain.booking.interceptor;

import com.squirrel.tickting.domain.booking.service.HoldLimitService;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 좌석 점유 앞에서 계정의 요청 횟수를 센다. 컨트롤러보다 앞에서 세어서 좌석을 못 잡는 요청(이미 잡힌 좌석, 잘못된 값)도 모두 센다.
 * 대상 경로는 WebMvcConfig 에서 정한다. 대기열 입장 확인 뒤에 걸어서, 입장하지 않아 거절된 요청은 세지 않는다.
 */
@Component
@RequiredArgsConstructor
public class HoldLimitInterceptor implements HandlerInterceptor {

	private final HoldLimitService holdLimitService;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return true;
		}
		// 로그인·계정 종류는 시큐리티가 먼저 확인하므로(USER_ONLY_PATHS) 여기 오는 점유 요청은 로그인한 예매자다.
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails account) {
			holdLimitService.count(account.getId());
		}
		return true;
	}
}
