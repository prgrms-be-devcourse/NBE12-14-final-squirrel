package com.squirrel.tickting.global.security;

import com.squirrel.tickting.global.jwt.JwtAuthenticationFilter;
import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.jwt.JwtProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 *  - /api/v1/admin/**     관리자만
 *  - /api/v1/seller/**    판매자만 (대표·담당자). 대표만 가능한 기능은 메서드에 @PreAuthorize("hasRole('SELLER_OWNER')")
 *  - 그 밖                 로그인 필요
 *
 * 로그인 없이 볼 수 있어야 하는 조회 API(공연 목록 등)는 해당 컨트롤러를 만들 때
 * 아래 requestMatchers 에 GET 경로를 permitAll 로 추가한다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

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
				.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
				.requestMatchers("/api/v1/seller/**").hasRole("SELLER")
				.anyRequest().authenticated())
			.addFilterBefore(new JwtAuthenticationFilter(jwtProvider, exceptionResolver),
				UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
