package com.squirrel.tickting.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.captcha.interceptor.CaptchaInterceptor;
import com.squirrel.tickting.domain.captcha.verifier.CaptchaVerifier;
import com.squirrel.tickting.domain.queue.service.QueueProperties;
import com.squirrel.tickting.domain.queue.service.QueueService;
import com.squirrel.tickting.domain.seller.service.SellerMemberService;
import com.squirrel.tickting.global.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경로별 접근 규칙과 401/403 응답 형식을 검증한다.
 * DB 없이 웹 계층만 띄우고, 테스트 전용 컨트롤러로 각 경로를 호출한다.
 */
@WebMvcTest(SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, SecurityConfigTest.TestController.class, SecurityConfigTest.QueueStub.class})
class SecurityConfigTest {

	@Autowired
	MockMvc mockMvc;

	/** @WebMvcTest 는 대기열·캡챠 인터셉터도 만든다. 이 테스트는 경로 규칙만 보므로 꺼진(enabled=false) 대기열을 넣어 통과시킨다. */
	@TestConfiguration
	static class QueueStub {

		@Bean
		QueueProperties queueProperties() {
			return new QueueProperties(false, 1, Duration.ofMinutes(1), Duration.ofSeconds(30));
		}

		@Bean
		QueueService queueService(QueueProperties properties) {
			return new QueueService(new StringRedisTemplate(), properties, new SimpleMeterRegistry());
		}

		/** 캡챠 인터셉터도 만들어지므로 항상 통과하는 검증기를 넣는다. 캡챠 자체는 CaptchaApiTest 가 확인한다. */
		@Bean
		CaptchaVerifier captchaVerifier() {
			return (token, action) -> true;
		}

		/**
		 * 판매자 계정 상태 인터셉터도 만들어진다. 이 테스트는 경로 규칙만 보므로 모든 계정을 사용 중으로 답하는 대역을 넣는다.
		 * (Mockito 대역의 boolean 기본값이 false 라서 isDisabled 는 항상 false 다. 차단은 SellerMemberApiTest 가 확인한다)
		 */
		@Bean
		SellerMemberService sellerMemberService() {
			return Mockito.mock(SellerMemberService.class);
		}

		/** 캡챠 인터셉터 팩토리는 HandlerInterceptor 가 아니라서 @WebMvcTest 가 만들어 주지 않는다. WebMvcConfig 가 쓰므로 직접 넣는다. */
		@Bean
		CaptchaInterceptor captchaInterceptor(CaptchaVerifier captchaVerifier) {
			return new CaptchaInterceptor(captchaVerifier);
		}
	}

	@RestController
	static class TestController {

		@GetMapping("/api/v1/auth/ping")
		String authPing() {
			return "ok";
		}

		@GetMapping("/api/v1/me/ping")
		String loggedInPing() {
			return "ok";
		}

		@GetMapping("/api/v1/admin/ping")
		String adminPing() {
			return "ok";
		}

		@GetMapping("/api/v1/seller/ping")
		String sellerPing() {
			return "ok";
		}

		@GetMapping("/api/v1/seller/owner-only")
		@PreAuthorize("hasRole('SELLER_OWNER')")
		String sellerOwnerOnly() {
			return "ok";
		}

		// 공개 조회 경로. 실제 컨트롤러가 만들어지기 전에도 접근 규칙을 확인하려고 같은 경로로 둔다.
		@GetMapping({"/api/v1/terms", "/api/v1/performances", "/api/v1/performances/{id}",
			"/api/v1/schedules/{id}/zones", "/api/v1/schedules/{id}/zones/{zoneId}/seats",
			"/api/v1/bookings"})
		String publicRead() {
			return "ok";
		}

		@PostMapping({"/api/v1/performances", "/api/v1/schedules/{id}/bookings", "/api/v1/payments/confirm",
			"/api/v1/bookings/{id}/cancel", "/api/v1/bookings/{id}/payments", "/api/v1/schedules/{id}/queue"})
		String loggedInWrite() {
			return "ok";
		}

		@GetMapping({"/api/v1/users/me", "/api/v1/bookings/{id}", "/api/v1/schedules/{id}/queue"})
		String userOnlyRead() {
			return "ok";
		}
	}

	/** 로그인한 세션. 세션에 저장된 SecurityContext 를 시큐리티가 복원한다. (Redis 는 쓰지 않는다) */
	private MockHttpSession login(Long id, AccountType type, String role, Long businessId) {
		CustomUserDetails account = new CustomUserDetails(id, type, role, businessId);
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
			UsernamePasswordAuthenticationToken.authenticated(account, null, account.getAuthorities())));
		return session;
	}

	@Test
	@DisplayName("로그인 경로(/api/v1/auth/**)는 로그인 없이 호출할 수 있다")
	void authPathIsPublic() throws Exception {
		mockMvc.perform(get("/api/v1/auth/ping"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("로그인 없이 보호된 경로를 호출하면 401 과 AUTH200 을 ApiResponse 형식으로 돌려준다")
	void noLoginIs401() throws Exception {
		mockMvc.perform(get("/api/v1/me/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("로그인한 예매자는 로그인만 필요한 경로를 호출할 수 있다")
	void userCanCallLoggedInPath() throws Exception {
		mockMvc.perform(get("/api/v1/me/ping")
				.session(login(1L, AccountType.USER, null, null)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("예매자가 관리자 경로를 호출하면 403 과 AUTH100 이다")
	void userCannotCallAdminPath() throws Exception {
		mockMvc.perform(get("/api/v1/admin/ping")
				.session(login(1L, AccountType.USER, null, null)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("관리자는 관리자 경로를 호출할 수 있다")
	void adminCanCallAdminPath() throws Exception {
		mockMvc.perform(get("/api/v1/admin/ping")
				.session(login(1L, AccountType.ADMIN, null, null)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("관리자는 판매자 경로를 호출할 수 없다 (계정 종류가 다르다)")
	void adminCannotCallSellerPath() throws Exception {
		mockMvc.perform(get("/api/v1/seller/ping")
				.session(login(1L, AccountType.ADMIN, null, null)))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("판매자 담당자는 판매자 경로를 호출할 수 있다")
	void sellerStaffCanCallSellerPath() throws Exception {
		mockMvc.perform(get("/api/v1/seller/ping")
				.session(login(2L, AccountType.SELLER, "STAFF", 1L)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("대표 전용 기능은 대표만 호출할 수 있고, 담당자는 403 이다")
	void ownerOnlyEndpoint() throws Exception {
		mockMvc.perform(get("/api/v1/seller/owner-only")
				.session(login(1L, AccountType.SELLER, "OWNER", 1L)))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/seller/owner-only")
				.session(login(2L, AccountType.SELLER, "STAFF", 1L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("약관, 공연 목록·상세, 구역 요약, 구역 좌석 상세는 로그인 없이 GET 으로 볼 수 있다")
	void publicReadPathsAreOpen() throws Exception {
		for (String path : List.of("/api/v1/terms", "/api/v1/performances", "/api/v1/performances/12",
			"/api/v1/schedules/31/zones", "/api/v1/schedules/31/zones/12/seats")) {
			mockMvc.perform(get(path)).andExpect(status().isOk());
		}
	}

	@Test
	@DisplayName("공개 조회 경로라도 같은 경로의 쓰기 요청(POST)은 로그인이 필요하다")
	void writeOnPublicPathNeedsLogin() throws Exception {
		mockMvc.perform(post("/api/v1/performances"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("좌석 점유(POST)는 로그인하지 않으면 401 이고, 로그인한 예매자는 호출할 수 있다")
	void bookingNeedsLogin() throws Exception {
		mockMvc.perform(post("/api/v1/schedules/31/bookings"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));

		mockMvc.perform(post("/api/v1/schedules/31/bookings")
				.session(login(1L, AccountType.USER, null, null)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("공개 목록이 아닌 내 예매 목록은 로그인 없이 볼 수 없다")
	void myBookingsAreNotPublic() throws Exception {
		mockMvc.perform(get("/api/v1/bookings"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("예매자 전용 경로는 판매자·관리자 계정으로 호출하면 403 AUTH100 이다 (번호가 같아도 남의 예매가 보이지 않게)")
	void userOnlyPathsRejectSellerAndAdmin() throws Exception {
		List<MockHttpSession> tokens = List.of(
			login(1L, AccountType.SELLER, "OWNER", 1L),
			login(2L, AccountType.SELLER, "STAFF", 1L),
			login(1L, AccountType.ADMIN, null, null));

		for (MockHttpSession token : tokens) {
			for (var request : List.of(
				get("/api/v1/users/me"),
				get("/api/v1/bookings"),
				get("/api/v1/bookings/1"),
				post("/api/v1/schedules/31/bookings"),
				post("/api/v1/schedules/31/queue"),
				post("/api/v1/bookings/1/payments"),
				post("/api/v1/bookings/1/cancel"),
				post("/api/v1/payments/confirm"))) {
				mockMvc.perform(request.session(token))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.code").value("AUTH100"));
			}
		}
	}

	@Test
	@DisplayName("예매자는 예매자 전용 경로를 호출할 수 있다")
	void userCanCallUserOnlyPaths() throws Exception {
		MockHttpSession token = login(5L, AccountType.USER, null, null);

		for (var request : List.of(
			get("/api/v1/users/me"),
			get("/api/v1/bookings"),
			get("/api/v1/bookings/1"),
			post("/api/v1/schedules/31/bookings"),
			post("/api/v1/schedules/31/queue"),
			post("/api/v1/bookings/1/payments"),
			post("/api/v1/bookings/1/cancel"),
			post("/api/v1/payments/confirm"))) {
			mockMvc.perform(request.session(token)).andExpect(status().isOk());
		}
	}

	@Test
	@DisplayName("로그인한 예매자도 공개 조회를 그대로 볼 수 있다")
	void loggedInUserCanReadPublicPaths() throws Exception {
		mockMvc.perform(get("/api/v1/performances")
				.session(login(1L, AccountType.USER, null, null)))
			.andExpect(status().isOk());
	}
}
