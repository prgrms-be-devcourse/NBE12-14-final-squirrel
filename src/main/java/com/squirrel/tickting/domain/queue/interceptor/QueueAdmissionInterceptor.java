package com.squirrel.tickting.domain.queue.interceptor;

import com.squirrel.tickting.domain.queue.service.QueueProperties;
import com.squirrel.tickting.domain.queue.service.QueueService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.exception.code.QueueExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 구역 요약·구역 좌석 조회와 좌석 점유 앞에서 "이 회차에 입장한 예매자인가"를 확인한다.
 * 컨트롤러·서비스를 고치지 않고 바깥에서 거는 방식이라, 해당 API 를 만든 도메인 코드는 대기열을 모른다.
 * 대상 경로는 WebMvcConfig 에서 정한다.
 *
 * <p>구역 요약·구역 좌석 조회는 SecurityConfig 에서 로그인 없이 열려 있다.
 * 그래서 로그인 여부와 계정 종류(예매자)도 여기서 같이 확인한다. 점유 경로는 시큐리티가 먼저 막는다.
 */
@Component
@RequiredArgsConstructor
public class QueueAdmissionInterceptor implements HandlerInterceptor {

	private static final String SCHEDULE_ID = "scheduleId";

	private final QueueService queueService;
	private final QueueProperties properties;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!properties.enabled() || HttpMethod.OPTIONS.matches(request.getMethod())) {
			return true;
		}

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails account)) {
			throw new BusinessException(AuthExceptionCode.TOKEN_MISSING);
		}
		if (account.getAccountType() != AccountType.USER) {
			throw new BusinessException(AuthExceptionCode.ACCESS_DENIED);
		}

		@SuppressWarnings("unchecked")
		Map<String, String> pathVariables = (Map<String, String>) request.getAttribute(
			HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		Long scheduleId = Long.valueOf(pathVariables.get(SCHEDULE_ID));
		if (!queueService.isAdmitted(account.getId(), scheduleId)) {
			throw new BusinessException(QueueExceptionCode.NOT_ADMITTED);
		}
		return true;
	}
}
