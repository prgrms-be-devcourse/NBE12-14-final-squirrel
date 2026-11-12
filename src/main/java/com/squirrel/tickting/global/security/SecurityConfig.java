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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 시큐리티 설정. 세션 없이 JWT 로만 인증한다.
 *
 * 경로별 접근 규칙
 *  - /api/v1/auth/**      누구나 (로그인, 가입, 토큰 재발급)
 *  - Swagger, health      누구나
 *  - 공개 조회 (GET)       누구나 (약관, 공연 목록·상세, 구역 요약·좌석 상세)
 *  - /api/v1/admin/**     관리자만
 *  - /api/v1/seller/**    판매자만 (대표·담당자). 대표만 가능한 기능은 메서드에 @PreAuthorize("hasRole('SELLER_OWNER')")
 *  - 그 밖                 로그인 필요
 *
 * 로그인 없이 볼 수 있어야 하는 조회 API 를 새로 만들면 PUBLIC_READ_PATHS 에 GET 경로를 추가한다.
 * GET 만 연다. 같은 경로의 쓰기 요청(POST 등)은 로그인이 필요하다.
 * 공개 조회라도 Authorization 헤더에 잘못된 토큰을 보내면 401 이다. (토큰이 만료됐을 때 프론트가 재발급하게 하려는 것)
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
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint(new JwtAuthenticationEntryPoint(exceptionResolver))
				.accessDeniedHandler(new JwtAccessDeniedHandler(exceptionResolver)))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/v1/auth/**").permitAll()
				.requestMatchers("/actuator/health", "/actuator/info").permitAll()
				.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
				.requestMatchers(HttpMethod.GET, PUBLIC_READ_PATHS).permitAll()
				.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
				.requestMatchers("/api/v1/seller/**").hasRole("SELLER")
				.anyRequest().authenticated())
			.addFilterBefore(new JwtAuthenticationFilter(jwtProvider, exceptionResolver),
				UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
