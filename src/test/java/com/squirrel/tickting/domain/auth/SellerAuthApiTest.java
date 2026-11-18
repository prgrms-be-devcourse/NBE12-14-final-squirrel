package com.squirrel.tickting.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

	private static final String EMAIL = "owner@company.com";
	private static final String BUSINESS_NUMBER = "1234567890";

	private MvcResult signup(String email, String ci, String businessNumber) throws Exception {
		return postJson(SIGNUP, sellerSignupBody(email, ci, businessNumber))
			.andExpect(status().isCreated()).andReturn();
	}

	private MvcResult login(String email, String password) throws Exception {
		return postJson(LOGIN, loginBody(email, password)).andReturn();
	}

	/** 승인·반려·정지 상태를 빠르게 만들려고 승인 API 를 거치지 않고 DB 의 사업체 상태를 직접 바꾼다. */
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

	// ---------------------------------------------------------------- 로그인과 토큰

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
