package com.squirrel.tickting.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 관리자 사업체 반려. 상태를 정해야 하는 경우는 사업체를 DB 에 직접 넣고, 반려 후 가입·로그인 확인은 실제 API 로 만든다. */
class AdminSellerBusinessRejectApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/admin/seller-businesses/{id}/reject";
	private static final Long ADMIN_ID = 7L;
	private static final String REASON = "사업자등록번호 확인이 필요합니다.";

	private String adminToken() {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(ADMIN_ID, AccountType.ADMIN, null, null));
	}

	private ResultActions reject(long businessId, String reason) throws Exception {
		return mockMvc.perform(post(URL, businessId)
			.header("Authorization", adminToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("reason", reason))));
	}

	private long insertBusiness(String status, String businessNumber) {
		Timestamp time = Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 10, 0));
		return jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone,
			                             contact_email, terms_agreed_at, status, created_at, updated_at)
			VALUES ('가상기획', '김대표', ?, '0212345678', 'contact@company.com', ?, ?, ?, ?)
			RETURNING id
			""", Long.class, businessNumber, time, status, time, time);
	}

	private String statusOf(long businessId) {
		return jdbc.queryForObject("SELECT status FROM seller_business WHERE id = ?", String.class, businessId);
	}

	private int auditCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
	}

	// ---------------------------------------------------------------- 반려

	@Test
	@DisplayName("승인 대기 사업체를 반려하면 상태, 사유(앞뒤 공백 제거), 처리한 관리자, 처리 시각이 기록되고 감사 로그가 한 줄 남는다")
	void rejectPending() throws Exception {
		long businessId = insertBusiness("PENDING", "1234567890");

		reject(businessId, "  " + REASON + "  ")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.businessId").value(businessId))
			.andExpect(jsonPath("$.data.status").value("REJECTED"))
			.andExpect(jsonPath("$.data.rejectReason").value(REASON))
			.andExpect(jsonPath("$.data.reviewedAt").isNotEmpty());

		Map<String, Object> business = jdbc.queryForMap("SELECT * FROM seller_business WHERE id = ?", businessId);
		assertThat(business.get("status")).isEqualTo("REJECTED");
		assertThat(business.get("reject_reason")).isEqualTo(REASON);
		assertThat(business.get("reviewed_by")).isEqualTo(ADMIN_ID);
		assertThat(business.get("reviewed_at")).isNotNull();

		assertThat(auditCount()).isEqualTo(1);
		Map<String, Object> audit = jdbc.queryForMap("""
			SELECT actor_type, actor_id, business_id, target_type, target_id, action,
			       before_value ->> 'status' AS before_status,
			       after_value ->> 'status' AS after_status,
			       after_value ->> 'rejectReason' AS after_reason
			FROM audit_log
			""");
		assertThat(audit.get("actor_type")).isEqualTo("ADMIN");
		assertThat(audit.get("actor_id")).isEqualTo(ADMIN_ID);
		// 판매자 이력 화면에 관리자 처리 로그가 섞이지 않도록 사업체 번호는 비운다
		assertThat(audit.get("business_id")).isNull();
		assertThat(audit.get("target_type")).isEqualTo("SELLER_BUSINESS");
		assertThat(audit.get("target_id")).isEqualTo(businessId);
		assertThat(audit.get("action")).isEqualTo("REJECT");
		assertThat(audit.get("before_status")).isEqualTo("PENDING");
		assertThat(audit.get("after_status")).isEqualTo("REJECTED");
		assertThat(audit.get("after_reason")).isEqualTo(REASON);
	}

	@Test
	@DisplayName("반려된 판매자는 로그인할 수 없고(SELLER004), 같은 사업자번호로 다시 신청할 수도 없다(SELLER001)")
	void rejectedBusinessCannotLoginOrReapply() throws Exception {
		MvcResult signup = postJson("/api/v1/auth/sellers/signup",
			sellerSignupBody("owner@company.com", "ci-owner", "1234567890"))
			.andExpect(status().isCreated())
			.andReturn();
		int businessId = JsonPath.read(signup.getResponse().getContentAsString(), "$.data.businessId");

		reject(businessId, REASON).andExpect(status().isOk());

		postJson("/api/v1/auth/sellers/login", loginBody("owner@company.com", PASSWORD))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("SELLER004"));

		// 재신청은 지원하지 않는다. 사업자번호 중복 검사가 반려된 사업체도 포함해서 막는다
		postJson("/api/v1/auth/sellers/signup", sellerSignupBody("again@company.com", "ci-again", "1234567890"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SELLER001"));
	}

	// ---------------------------------------------------------------- 사유 검증

	@Test
	@DisplayName("사유가 비어 있거나 공백뿐이거나 없거나 501자이면 400 이고, 상태와 감사 로그는 그대로다")
	void invalidReasonIsBadRequest() throws Exception {
		long businessId = insertBusiness("PENDING", "1234567890");

		reject(businessId, "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		reject(businessId, "   ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		reject(businessId, "가".repeat(501))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));

		// reason 키가 없는 본문, 본문이 아예 없는 요청
		mockMvc.perform(post(URL, businessId).header("Authorization", adminToken())
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));
		mockMvc.perform(post(URL, businessId).header("Authorization", adminToken())
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON000"));

		assertThat(statusOf(businessId)).isEqualTo("PENDING");
		assertThat(auditCount()).isZero();
	}

	@Test
	@DisplayName("사유가 정확히 500자이면 반려된다")
	void reasonOfMaxLengthIsAccepted() throws Exception {
		long businessId = insertBusiness("PENDING", "1234567890");

		reject(businessId, "가".repeat(500)).andExpect(status().isOk());

		assertThat(statusOf(businessId)).isEqualTo("REJECTED");
	}

	// ---------------------------------------------------------------- 이미 처리된 건, 없는 건

	@Test
	@DisplayName("이미 승인·반려·정지된 사업체는 409 SELLER007 이고, 상태·사유·감사 로그는 그대로다")
	void alreadyReviewedIsConflict() throws Exception {
		String[] reviewedStatuses = {"APPROVED", "REJECTED", "SUSPENDED"};
		for (int i = 0; i < reviewedStatuses.length; i++) {
			String reviewed = reviewedStatuses[i];
			// 사업자번호는 중복될 수 없어서 상태마다 다르게 넣는다
			long businessId = insertBusiness(reviewed, "100000000" + i);

			reject(businessId, REASON)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("SELLER007"));

			assertThat(statusOf(businessId)).isEqualTo(reviewed);
			assertThat(jdbc.queryForObject("SELECT reject_reason FROM seller_business WHERE id = ?", String.class,
				businessId)).isNull();
		}
		assertThat(auditCount()).isZero();
	}

	@Test
	@DisplayName("승인한 사업체를 곧바로 반려할 수는 없고, 같은 사업체를 두 번 반려하면 두 번째는 409 이다")
	void cannotRejectApprovedOrRejectTwice() throws Exception {
		long approved = insertBusiness("PENDING", "1234567890");
		mockMvc.perform(post("/api/v1/admin/seller-businesses/{id}/approve", approved)
			.header("Authorization", adminToken())).andExpect(status().isOk());
		reject(approved, REASON).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER007"));

		long pending = insertBusiness("PENDING", "1234567891");
		reject(pending, REASON).andExpect(status().isOk());
		reject(pending, "다른 사유").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER007"));

		assertThat(statusOf(approved)).isEqualTo("APPROVED");
		// 처음 반려 때의 사유가 그대로 남는다
		assertThat(jdbc.queryForObject("SELECT reject_reason FROM seller_business WHERE id = ?", String.class,
			pending)).isEqualTo(REASON);
		// 승인 한 줄 + 반려 한 줄. 거절된 요청은 로그를 남기지 않는다
		assertThat(auditCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("없는 사업체는 404 SELLER006 이고 감사 로그는 남지 않는다")
	void notFound() throws Exception {
		reject(999999L, REASON)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SELLER006"));

		assertThat(auditCount()).isZero();
	}

	@Test
	@DisplayName("사업체 번호가 숫자가 아니면 400 이다")
	void invalidIdIsBadRequest() throws Exception {
		mockMvc.perform(post(URL, "abc").header("Authorization", adminToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reason", REASON))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}

	// ---------------------------------------------------------------- 권한

	@Test
	@DisplayName("토큰이 없으면 401, 예매자·판매자(대표·담당자) 토큰이면 403 이고 사업체는 반려되지 않는다")
	void onlyAdminCanReject() throws Exception {
		long businessId = insertBusiness("PENDING", "1234567890");
		String body = objectMapper.writeValueAsString(Map.of("reason", REASON));

		mockMvc.perform(post(URL, businessId).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));

		// 판매자가 자기 사업체 번호로 호출해도 열리지 않는다
		for (CustomUserDetails notAdmin : Set.of(
			new CustomUserDetails(1L, AccountType.USER, null, null),
			new CustomUserDetails(1L, AccountType.SELLER, "OWNER", businessId),
			new CustomUserDetails(2L, AccountType.SELLER, "STAFF", businessId))) {
			mockMvc.perform(post(URL, businessId)
					.header("Authorization", "Bearer " + jwtProvider.createAccessToken(notAdmin))
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH100"));
		}

		assertThat(statusOf(businessId)).isEqualTo("PENDING");
		assertThat(auditCount()).isZero();
	}
}
