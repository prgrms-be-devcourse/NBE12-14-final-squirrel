package com.squirrel.tickting.global.jwt;

import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Authorization: Bearer {accessToken} 헤더를 읽어 로그인 사용자를 SecurityContext 에 넣는다.
 *
 * - 헤더가 없으면 그냥 통과시킨다. (인증이 필요한 경로는 시큐리티가 401 을 낸다)
 * - 토큰이 있는데 잘못됐거나 만료됐으면 여기서 바로 401 을 낸다.
 *
 * 필터에서 난 예외는 컨트롤러 밖이라 @RestControllerAdvice 가 자동으로 못 잡는다.
 * HandlerExceptionResolver 에 넘겨서 GlobalExceptionHandler 가 같은 형식으로 응답하게 한다.
 *
 * @Component 로 등록하지 않는다. 등록하면 서블릿 필터로 한 번 더 실행된다.
 * SecurityConfig 에서 직접 만들어 시큐리티 필터 체인에만 넣는다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtProvider jwtProvider;
	private final HandlerExceptionResolver exceptionResolver;

	public JwtAuthenticationFilter(JwtProvider jwtProvider, HandlerExceptionResolver exceptionResolver) {
		this.jwtProvider = jwtProvider;
		this.exceptionResolver = exceptionResolver;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
		FilterChain filterChain) throws ServletException, IOException {

		String header = request.getHeader("Authorization");
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			CustomUserDetails account = jwtProvider.parseAccessToken(header.substring(BEARER_PREFIX.length()));
			var authentication = new UsernamePasswordAuthenticationToken(account, null, account.getAuthorities());
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} catch (BusinessException e) {
			SecurityContextHolder.clearContext();
			exceptionResolver.resolveException(request, response, null, e);
			return;
		}

		filterChain.doFilter(request, response);
	}
}
