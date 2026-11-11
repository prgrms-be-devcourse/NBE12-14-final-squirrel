package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class SellerAuthApiTest extends IntegrationTestSupport {

	private static final String SIGNUP = "/api/v1/auth/sellers/signup";
	private static final String LOGIN = "/api/v1/auth/sellers/login";
	private static final String REFRESH = "/api/v1/auth/refresh";
	private static final String ME = "/api/v1/me";

	private static final String EMAIL = "owner@company.com";
	private static final String BUSINESS_NUMBER = "1234567890";

	private MvcResult signup(String email, String ci, String businessNumber) throws Exception {
		return postJson(SIGNUP, sellerSignupBody(email, ci, businessNumber))
			.andExpect(status().isCreated()).andReturn();
	}

	private MvcResult login(String email, String password) throws Exception {
		return postJson(LOGIN, loginBody(email, password)).andReturn();
	}

	/** 관리자 승인 API 가 아직 없어서, DB 의 사업체 상태를 직접 바꿔 승인·반려·정지를 흉내 낸다. */
	private void setBusinessStatus(String businessNumber, String status) {
		jdbc.update("UPDATE seller_business SET status = ? WHERE business_number = ?", status, businessNumber);
	}

	private MvcResult signupAndApprove() throws Exception {
		signup(EMAIL, "ci-owner", BUSINESS_NUMBER);
		setBusinessStatus(BUSINESS_NUMBER, "APPROVED");
		return postJson(LOGIN, loginBody(EMAIL, PASSWORD)).andExpect(status().isOk()).andReturn();
	}

	// ---------------------------------------------------------------- 가입 신청

	@Test
	@DisplayName("가입 신청하면 사업체(승인 대기)와 대표자 계정이 함께 만들어지고, 토큰은 주지 않는다")
	void signupCreatesPendingBusinessAndOwner() throws Exception {
		MvcResult result = postJson(SIGNUP, sellerSignupBody("Owner@Company.com", "ci-owner", BUSINESS_NUMBER))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("PENDING"))
			.andExpect(jsonPath("$.data.businessId").isNumber())
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain("accessToken");
		assertThat(refreshCookie(result)).isNull();

		Map<String, Object> business = jdbc.queryForMap("SELECT * FROM seller_business");
		assertThat(business.get("status")).isEqualTo("PENDING");
		assertThat(business.get("representative_name")).isEqualTo("김대표");
		assertThat(business.get("contact_email")).isEqualTo("contact@company.com");

		Map<String, Object> member = jdbc.queryForMap("SELECT * FROM seller_member");
		assertThat(member.get("business_id")).isEqualTo(business.get("id"));
		assertThat(member.get("role")).isEqualTo("OWNER");
		assertThat(member.get("status")).isEqualTo("ACTIVE");
		assertThat(member.get("email")).isEqualTo(EMAIL);
		assertThat((String) member.get("password")).startsWith("$2").isNotEqualTo(PASSWORD);
		assertThat((String) member.get("ci_hash")).hasSize(64);
	}

	@Test
	@DisplayName("본인인증 이름과 대표자명이 다르면 400 이고 아무것도 저장되지 않는다")
	void representativeNameMismatch() throws Exception {
		Map<String, Object> body = sellerSignupBody(EMAIL, "ci-owner", BUSINESS_NUMBER);
		body.put("representativeName", "다른사람");

		postJson(SIGNUP, body)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("SELLER002"));

		assertThat(count("seller_business")).isZero();
		assertThat(count("seller_member")).isZero();
	}

	@Test
	@DisplayName("사업자번호가 이미 있으면 409 이고 사업체·계정이 더 생기지 않는다")
	void businessNumberDuplicate() throws Exception {
		signup(EMAIL, "ci-owner", BUSINESS_NUMBER);

		postJson(SIGNUP, sellerSignupBody("other@company.com", "ci-other", BUSINESS_NUMBER))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SELLER001"));

		assertThat(count("seller_business")).isEqualTo(1);
		assertThat(count("seller_member")).isEqualTo(1);
	}

	@Test
	@DisplayName("이메일이 이미 있으면 409 이고, 사업체만 따로 남지 않는다 (둘은 한 묶음이다)")
	void emailDuplicateLeavesNoOrphanBusiness() throws Exception {
		signup(EMAIL, "ci-owner", BUSINESS_NUMBER);

		postJson(SIGNUP, sellerSignupBody(" OWNER@company.com ", "ci-other", "9876543210"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SELLER000"));

		assertThat(count("seller_business")).isEqualTo(1);
		assertThat(count("seller_member")).isEqualTo(1);
	}

	@Test
	@DisplayName("사업자번호 형식, 약관 동의, 필수 값이 빠지면 400 이다")
	void invalidFields() throws Exception {
		for (String badNumber : List.of("123-45-67890", "12345", "abcdefghij", "12345678901")) {
			postJson(SIGNUP, sellerSignupBody(EMAIL, "ci-owner", badNumber))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON000"));
		}

		Map<String, Object> noTerms = sellerSignupBody(EMAIL, "ci-owner", BUSINESS_NUMBER);
		noTerms.put("termsAgreed", false);
		postJson(SIGNUP, noTerms).andExpect(status().isBadRequest());

		Map<String, Object> noCompany = sellerSignupBody(EMAIL, "ci-owner", BUSINESS_NUMBER);
		noCompany.put("companyName", " ");
		postJson(SIGNUP, noCompany).andExpect(status().isBadRequest());

		Map<String, Object> badToken = sellerSignupBody(EMAIL, "ci-owner", BUSINESS_NUMBER);
		badToken.put("identityToken", "garbage");
		postJson(SIGNUP, badToken).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH300"));

		assertThat(count("seller_business")).isZero();
		assertThat(count("seller_member")).isZero();
	}

	@Test
	@DisplayName("같은 사람이 다른 사업체의 대표로 가입하는 것은 막지 않는다 (CI 는 사업체 안에서만 유일)")
	void samePersonCanOwnAnotherBusiness() throws Exception {
		signup(EMAIL, "ci-owner", BUSINESS_NUMBER);

		postJson(SIGNUP, sellerSignupBody("second@company.com", "ci-owner", "9876543210"))
			.andExpect(status().isCreated());

		assertThat(count("seller_business")).isEqualTo(2);
	}

	@Test
	@DisplayName("같은 이메일로 예매자와 판매자 계정을 따로 만들 수 있고, 각자의 로그인 주소로 각자의 토큰을 받는다")
	void sameEmailForUserAndSeller() throws Exception {
		postJson("/api/v1/auth/users/signup", userSignupBody(EMAIL, "ci-user")).andExpect(status().isCreated());
		signup(EMAIL, "ci-owner", BUSINESS_NUMBER);
		setBusinessStatus(BUSINESS_NUMBER, "APPROVED");

		MvcResult userLogin = postJson("/api/v1/auth/users/login", loginBody(EMAIL, PASSWORD))
			.andExpect(status().isOk()).andReturn();
		MvcResult sellerLogin = postJson(LOGIN, loginBody(EMAIL, PASSWORD))
			.andExpect(status().isOk()).andReturn();

		assertThat(jwtProvider.parseAccessToken(accessToken(userLogin)).getAccountType())
			.isEqualTo(AccountType.USER);
		assertThat(jwtProvider.parseAccessToken(accessToken(sellerLogin)).getAccountType())
			.isEqualTo(AccountType.SELLER);
	}

	// ---------------------------------------------------------------- 로그인과 토큰

	@Test
	@DisplayName("승인된 판매자는 로그인하고, 토큰에 판매자·대표·사업체 번호가 담긴다")
	void approvedSellerGetsTokenWithRoleAndBusiness() throws Exception {
		MvcResult result = signupAndApprove();

		assertThat(refreshCookie(result)).isNotBlank();
		CustomUserDetails parsed = jwtProvider.parseAccessToken(accessToken(result));
		Long businessId = jdbc.queryForObject("SELECT id FROM seller_business", Long.class);

		assertThat(parsed.getAccountType()).isEqualTo(AccountType.SELLER);
		assertThat(parsed.getRole()).isEqualTo("OWNER");
		assertThat(parsed.getBusinessId()).isEqualTo(businessId);

		mockMvc.perform(get(ME).header("Authorization", "Bearer " + accessToken(result)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accountType").value("SELLER"))
			.andExpect(jsonPath("$.data.role").value("OWNER"))
			.andExpect(jsonPath("$.data.businessId").value(businessId));
	}

	@Test
	@DisplayName("판매자 토큰은 판매자 경로를 통과하고(404 는 경로가 아직 없어서), 관리자 경로는 403 이다")
	void sellerTokenAccess() throws Exception {
		String token = accessToken(signupAndApprove());

		mockMvc.perform(get("/api/v1/seller/anything").header("Authorization", "Bearer " + token))
			.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/admin/anything").header("Authorization", "Bearer " + token))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("승인 전에는 비밀번호가 맞아도 403 SELLER003 이다")
	void pendingBusinessCannotLogin() throws Exception {
		signup(EMAIL, "ci-owner", BUSINESS_NUMBER);

		MvcResult result = login(EMAIL, PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(read(result, "$.code")).isEqualTo("SELLER003");
		assertThat(refreshCookie(result)).isNull();
	}

	@Test
	@DisplayName("비밀번호가 틀린 사람에게는 사업체 상태를 알려 주지 않고 401 AUTH001 이다")
	void wrongPasswordDoesNotRevealBusinessStatus() throws Exception {
		signup(EMAIL, "ci-owner", BUSINESS_NUMBER);

		MvcResult wrongPassword = login(EMAIL, "wrong-password1!");
		MvcResult unknownEmail = login("nobody@company.com", PASSWORD);

		assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(wrongPassword, "$.code")).isEqualTo("AUTH001");
		assertThat(read(unknownEmail, "$.code")).isEqualTo("AUTH001");
		assertThat(read(wrongPassword, "$.message")).isEqualTo(read(unknownEmail, "$.message"));
	}

	@Test
	@DisplayName("반려·정지된 사업체와 비활성화된 계정은 로그인할 수 없다")
	void rejectedSuspendedAndInactive() throws Exception {
		signup(EMAIL, "ci-owner", BUSINESS_NUMBER);

		setBusinessStatus(BUSINESS_NUMBER, "REJECTED");
		assertThat(read(login(EMAIL, PASSWORD), "$.code")).isEqualTo("SELLER004");

		setBusinessStatus(BUSINESS_NUMBER, "SUSPENDED");
		assertThat(read(login(EMAIL, PASSWORD), "$.code")).isEqualTo("SELLER005");

		setBusinessStatus(BUSINESS_NUMBER, "APPROVED");
		jdbc.update("UPDATE seller_member SET status = 'INACTIVE'");
		MvcResult inactive = login(EMAIL, PASSWORD);
		assertThat(inactive.getResponse().getStatus()).isEqualTo(401);
		assertThat(read(inactive, "$.code")).isEqualTo("AUTH003");

		jdbc.update("UPDATE seller_member SET status = 'ACTIVE'");
		assertThat(login(EMAIL, PASSWORD).getResponse().getStatus()).isEqualTo(200);
	}

	// ---------------------------------------------------------------- 재발급

	@Test
	@DisplayName("재발급할 때 역할과 사업체는 토큰이 아니라 DB 값으로 다시 만든다")
	void refreshReadsRoleFromDatabase() throws Exception {
		MvcResult loginResult = signupAndApprove();
		String refresh = refreshCookie(loginResult);

		// 대표가 담당자로 바뀐 상황을 만든다
		jdbc.update("UPDATE seller_member SET role = 'STAFF'");

		MvcResult refreshed = mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("STAFF"))
			.andReturn();

		assertThat(jwtProvider.parseAccessToken(accessToken(refreshed)).getRole()).isEqualTo("STAFF");
	}

	@Test
	@DisplayName("사업체가 정지되거나 계정이 비활성화되면 이미 받은 리프레시 토큰으로도 재발급받을 수 없다")
	void refreshBlockedWhenBusinessOrAccountDisabled() throws Exception {
		String refresh = refreshCookie(signupAndApprove());

		setBusinessStatus(BUSINESS_NUMBER, "SUSPENDED");
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SELLER005"));

		setBusinessStatus(BUSINESS_NUMBER, "APPROVED");
		jdbc.update("UPDATE seller_member SET status = 'INACTIVE'");
		mockMvc.perform(post(REFRESH).cookie(new Cookie("refreshToken", refresh)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH003"));
	}

	@Test
	@DisplayName("예매자의 리프레시 토큰으로 판매자 토큰을 받을 수 없고, 계정 종류는 토큰의 값을 따른다")
	void refreshKeepsAccountType() throws Exception {
		MvcResult userSignup = postJson("/api/v1/auth/users/signup", userSignupBody("user@example.com", "ci-user"))
			.andExpect(status().isCreated()).andReturn();

		MvcResult refreshed = mockMvc.perform(post(REFRESH)
				.cookie(new Cookie("refreshToken", refreshCookie(userSignup))))
			.andExpect(status().isOk()).andReturn();

		assertThat(jwtProvider.parseAccessToken(accessToken(refreshed)).getAccountType())
			.isEqualTo(AccountType.USER);
	}

	// ---------------------------------------------------------------- 동시성

	@Test
	@DisplayName("같은 이메일로 동시에 가입 신청하면 한 건만 만들어지고, 사업체만 남는 일이 없다")
	void concurrentSignupSameEmail() throws Exception {
		List<MvcResult> results = runConcurrently(8, i -> () ->
			postJson(SIGNUP, sellerSignupBody(EMAIL, "ci-race-" + i, "10000000" + String.format("%02d", i)))
				.andReturn());

		assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201)).hasSize(1);
		for (MvcResult result : results) {
			if (result.getResponse().getStatus() != 201) {
				assertThat(result.getResponse().getStatus()).isEqualTo(409);
				assertThat(read(result, "$.code")).isEqualTo("SELLER000");
			}
		}
		assertThat(count("seller_member")).isEqualTo(1);
		assertThat(count("seller_business")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 사업자번호로 동시에 가입 신청하면 한 건만 만들어진다")
	void concurrentSignupSameBusinessNumber() throws Exception {
		List<MvcResult> results = runConcurrently(8, i -> () ->
			postJson(SIGNUP, sellerSignupBody("owner" + i + "@company.com", "ci-race-" + i, BUSINESS_NUMBER))
				.andReturn());

		assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201)).hasSize(1);
		for (MvcResult result : results) {
			if (result.getResponse().getStatus() != 201) {
				assertThat(result.getResponse().getStatus()).isEqualTo(409);
				assertThat(read(result, "$.code")).isEqualTo("SELLER001");
			}
		}
		assertThat(count("seller_business")).isEqualTo(1);
		assertThat(count("seller_member")).isEqualTo(1);
	}
}
