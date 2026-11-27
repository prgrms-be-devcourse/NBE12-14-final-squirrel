package com.squirrel.tickting.domain.seller.interceptor;

import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.AuthExceptionCode;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 비활성화된 판매자 계정의 남은 세션을 막는다. 대상 경로는 WebMvcConfig 에서 정한다.
 *
 * <p>시큐리티는 Redis 세션에 저장된 로그인 정보만 보고 DB 의 계정 상태를 보지 않는다. 세션은 요청이 올 때마다 연장돼서,
 * 이 확인이 없으면 대표가 비활성화한 담당자도 쓰던 브라우저로 계속 쓸 수 있다. 그래서 판매자 요청마다 DB 에서 계정 상태를 한 번 확인한다.
 * 판매자 요청은 예매처럼 몰리지 않아서 조회 하나를 더해도 부담이 작고, 예매자·관리자 요청은 조회하지 않는다.
 * 버린 대안: 비활성화할 때 그 계정의 세션을 찾아 지우기 (세션을 계정으로 찾으려면 모든 사용자의 세션 저장 방식을 바꿔야 함).
 */
@Component
@RequiredArgsConstructor
public class SellerAccountStatusInterceptor implements HandlerInterceptor {

	private final SellerMemberService sellerMemberService;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (HttpMethod.OPTIONS.matches(request.getMethod())) {
			return true;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails account)
			|| account.getAccountType() != AccountType.SELLER) {
			return true;
		}
		if (sellerMemberService.isDisabled(account.getId())) {
			// 같은 쿠키로 다시 와도 로그인 상태가 아니게 세션을 지운다
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			SecurityContextHolder.clearContext();
			throw new BusinessException(AuthExceptionCode.ACCOUNT_DISABLED);
		}
		return true;
	}
}
