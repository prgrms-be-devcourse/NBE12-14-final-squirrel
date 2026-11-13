package com.squirrel.tickting.global.security;

import com.squirrel.tickting.global.jwt.JwtAuthenticationFilter;
import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.jwt.JwtProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 시큐리티 설정. 인증은 두 방식을 모두 인정한다.
 *  - v2 Redis 세션 (권장): /api/v2/auth/** 로 로그인하면 SESSION 쿠키가 내려가고, 쿠키로 세션을 찾아 로그인 상태를 복원한다.
 *  - v1 JWT: Authorization: Bearer 헤더의 액세스 토큰. 헤더가 있으면 이쪽이 먼저 적용된다.
 * 업무 API 는 경로를 바꾸지 않고 어느 방식으로 로그인했든 같은 규칙으로 접근을 판단한다.
 *
 * 세션은 로그인 API 가 직접 만들 때만 생긴다. JWT 요청이나 로그인 안 한 요청이 세션을 만들지 않는다.
 *
 * 경로별 접근 규칙
 *  - /api/v1/auth/**, /api/v2/auth/**   누구나 (로그인, 가입, 로그아웃, v1 토큰 재발급)
 *  - Swagger, health      누구나
 *  - 공개 조회 (GET)       누구나 (약관, 공연 목록·상세, 구역 요약·좌석 상세)
 *  - 예매자 전용           예매자만 (내 정보, 좌석 점유·예매, 결제, 내 예매)
 *  - /api/v1/admin/**     관리자만
 *  - /api/v1/seller/**    판매자만 (대표·담당자). 대표만 가능한 기능은 메서드에 @PreAuthorize("hasRole('SELLER_OWNER')")
 *  - 그 밖                 로그인 필요 (계정 종류는 가리지 않는다)
 *
 * 새 API 를 만들면 어느 규칙에 들어가는지 확인한다.
 *  - 로그인 없이 볼 수 있는 조회 API  → PUBLIC_READ_PATHS 에 GET 경로 추가. GET 만 연다. 같은 경로의 쓰기 요청은 로그인이 필요하다.
 *  - 예매자만 쓰는 API               → USER_ONLY_PATHS 에 경로 추가. 빠뜨리면 판매자·관리자 토큰으로도 호출된다.
 * 공개 조회라도 Authorization 헤더에 잘못된 토큰을 보내면 401 이다. (토큰이 만료됐을 때 프론트가 재발급하게 하려는 것)
 *
 * 여기서는 "어떤 계정 종류인가"만 본다. "이 데이터가 내 것인가"는 서비스에서 토큰의 번호로 조회해서 확인한다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, AuthCookieProperties.class})
public class SecurityConfig {

	/** 로그인 없이 볼 수 있는 조회 API. 경로 변수는 * 로 적는다. */
	private static final String[] PUBLIC_READ_PATHS = {
		"/api/v1/terms",
		"/api/v1/performances",
		"/api/v1/performances/*",
		"/api/v1/schedules/*/zones",
		"/api/v1/schedules/*/zones/*/seats"
	};

	/**
	 * 예매자만 쓰는 API. 판매자·관리자와 계정 번호가 같아도 남의 예매가 보이지 않도록 계정 종류로 막는다.
	 * 예매 하위 경로(결제 준비, 취소)는 /bookings/** 에 포함된다.
	 */
	private static final String[] USER_ONLY_PATHS = {
		"/api/v1/users/**",
		"/api/v1/bookings/**",
		"/api/v1/payments/**",
		"/api/v1/schedules/*/bookings"
	};

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(
		HttpSecurity http,
		JwtProvider jwtProvider,
		@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver
	) throws Exception {
		http
			.csrf(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			// 시큐리티는 세션을 만들지 않고, 있으면 쓴다. 세션은 v2 로그인 API(SessionAuthenticator)만 직접 만든다.
			// IF_REQUIRED 로 두면 JWT 로 인증된 요청도 세션에 저장되어 Redis 가 쌓이고, 아래 requestCache 를 끄지 않으면
			// 로그인 안 한 요청마다 세션이 생긴다.
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
			.requestCache(RequestCacheConfigurer::disable)
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(new JwtAuthenticationEntryPoint(exceptionResolver))
				.accessDeniedHandler(new JwtAccessDeniedHandler(exceptionResolver)))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/v1/auth/**", "/api/v2/auth/**").permitAll()
				.requestMatchers("/actuator/health", "/actuator/info").permitAll()
				.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
				.requestMatchers(HttpMethod.GET, PUBLIC_READ_PATHS).permitAll()
				.requestMatchers(USER_ONLY_PATHS).hasRole("USER")
				.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
				.requestMatchers("/api/v1/seller/**").hasRole("SELLER")
				.anyRequest().authenticated())
			.addFilterBefore(new JwtAuthenticationFilter(jwtProvider, exceptionResolver),
				UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
