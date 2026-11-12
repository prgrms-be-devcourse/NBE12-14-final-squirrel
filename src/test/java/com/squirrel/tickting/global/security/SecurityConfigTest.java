package com.squirrel.tickting.global.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.exception.GlobalExceptionHandler;
import com.squirrel.tickting.global.jwt.JwtProperties;
import com.squirrel.tickting.global.jwt.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import com.squirrel.tickting.support.TestSecrets;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 경로별 접근 규칙과 401/403 응답 형식을 검증한다.
 * DB 없이 웹 계층만 띄우고, 테스트 전용 컨트롤러로 각 경로를 호출한다.
 */
@WebMvcTest(SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, JwtProvider.class, SecurityConfigTest.TestController.class})
class SecurityConfigTest {

	/** 서명 키는 코드에 적지 않고 실행할 때 만든다. */
	@DynamicPropertySource
	static void jwtSecret(DynamicPropertyRegistry registry) {
		registry.add("jwt.secret", TestSecrets::randomJwtSecret);
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtProvider jwtProvider;

	@Autowired
	JwtProperties jwtProperties;

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

		@PostMapping({"/api/v1/performances", "/api/v1/schedules/{id}/bookings"})
		String loggedInWrite() {
			return "ok";
		}
	}

	private String bearer(Long id, AccountType type, String role, Long businessId) {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(id, type, role, businessId));
	}

	@Test
	@DisplayName("로그인 경로(/api/v1/auth/**)는 토큰 없이 호출할 수 있다")
	void authPathIsPublic() throws Exception {
		mockMvc.perform(get("/api/v1/auth/ping"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("토큰 없이 보호된 경로를 호출하면 401 과 AUTH200 을 ApiResponse 형식으로 돌려준다")
	void noTokenIs401() throws Exception {
		mockMvc.perform(get("/api/v1/me/ping"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("위조된 토큰은 401 과 AUTH201 이다")
	void forgedTokenIs401() throws Exception {
		mockMvc.perform(get("/api/v1/me/ping").header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));
	}

	@Test
	@DisplayName("만료된 토큰은 401 과 AUTH202 이다")
	void expiredTokenIs401() throws Exception {
		var expired = new JwtProvider(new JwtProperties(
			jwtProperties.secret(), jwtProperties.issuer(), -10, -10));
		String token = expired.createAccessToken(new CustomUserDetails(1L, AccountType.USER, null, null));

		mockMvc.perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + token))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH202"));
	}

	@Test
	@DisplayName("리프레시 토큰으로는 API 를 호출할 수 없다")
	void refreshTokenCannotCallApi() throws Exception {
		String refresh = jwtProvider.createRefreshToken(1L, AccountType.USER);

		mockMvc.perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + refresh))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));
	}

	@Test
	@DisplayName("로그인한 예매자는 로그인만 필요한 경로를 호출할 수 있다")
	void userCanCallLoggedInPath() throws Exception {
		mockMvc.perform(get("/api/v1/me/ping")
				.header("Authorization", bearer(1L, AccountType.USER, null, null)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("예매자가 관리자 경로를 호출하면 403 과 AUTH100 이다")
	void userCannotCallAdminPath() throws Exception {
		mockMvc.perform(get("/api/v1/admin/ping")
				.header("Authorization", bearer(1L, AccountType.USER, null, null)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("관리자는 관리자 경로를 호출할 수 있다")
	void adminCanCallAdminPath() throws Exception {
		mockMvc.perform(get("/api/v1/admin/ping")
				.header("Authorization", bearer(1L, AccountType.ADMIN, null, null)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("관리자는 판매자 경로를 호출할 수 없다 (계정 종류가 다르다)")
	void adminCannotCallSellerPath() throws Exception {
		mockMvc.perform(get("/api/v1/seller/ping")
				.header("Authorization", bearer(1L, AccountType.ADMIN, null, null)))
			.andExpect(status().isForbidden());
	}

	@Test
	@DisplayName("판매자 담당자는 판매자 경로를 호출할 수 있다")
	void sellerStaffCanCallSellerPath() throws Exception {
		mockMvc.perform(get("/api/v1/seller/ping")
				.header("Authorization", bearer(2L, AccountType.SELLER, "STAFF", 1L)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("대표 전용 기능은 대표만 호출할 수 있고, 담당자는 403 이다")
	void ownerOnlyEndpoint() throws Exception {
		mockMvc.perform(get("/api/v1/seller/owner-only")
				.header("Authorization", bearer(1L, AccountType.SELLER, "OWNER", 1L)))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/seller/owner-only")
				.header("Authorization", bearer(2L, AccountType.SELLER, "STAFF", 1L)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("약관, 공연 목록·상세, 구역 요약, 구역 좌석 상세는 토큰 없이 GET 으로 볼 수 있다")
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
	@DisplayName("좌석 점유(POST)는 토큰이 없으면 401 이고, 로그인한 예매자는 호출할 수 있다")
	void bookingNeedsLogin() throws Exception {
		mockMvc.perform(post("/api/v1/schedules/31/bookings"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));

		mockMvc.perform(post("/api/v1/schedules/31/bookings")
				.header("Authorization", bearer(1L, AccountType.USER, null, null)))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("공개 목록이 아닌 내 예매 목록은 토큰 없이 볼 수 없다")
	void myBookingsAreNotPublic() throws Exception {
		mockMvc.perform(get("/api/v1/bookings"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
	}

	@Test
	@DisplayName("공개 조회에 잘못된 토큰을 보내면 401 AUTH201 이다 (만료되면 프론트가 재발급하게 하려는 것)")
	void invalidTokenOnPublicPathIs401() throws Exception {
		mockMvc.perform(get("/api/v1/performances").header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH201"));
	}

	@Test
	@DisplayName("로그인한 예매자도 공개 조회를 그대로 볼 수 있다")
	void loggedInUserCanReadPublicPaths() throws Exception {
		mockMvc.perform(get("/api/v1/performances")
				.header("Authorization", bearer(1L, AccountType.USER, null, null)))
			.andExpect(status().isOk());
	}
}
