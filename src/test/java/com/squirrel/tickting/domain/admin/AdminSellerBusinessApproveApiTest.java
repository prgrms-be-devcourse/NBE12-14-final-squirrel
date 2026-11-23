package com.squirrel.tickting.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** 관리자 사업체 승인. 상태를 정해야 하는 경우는 사업체를 DB 에 직접 넣고, 로그인 확인은 실제 가입 API 로 만든다. */
class AdminSellerBusinessApproveApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/admin/seller-businesses/{id}/approve";
	private static final Long ADMIN_ID = 7L;

	private String adminToken() {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(ADMIN_ID, AccountType.ADMIN, null, null));
	}

	private ResultActions approve(long businessId) throws Exception {
		return mockMvc.perform(post(URL, businessId).header("Authorization", adminToken()));
	}

	private long insertBusiness(String status) {
		Timestamp time = Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 10, 0));
		return jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone,
			                             contact_email, terms_agreed_at, status, created_at, updated_at)
			VALUES ('가상기획', '김대표', '1234567890', '0212345678', 'contact@company.com', ?, ?, ?, ?)
			RETURNING id
			""", Long.class, time, status, time, time);
	}

	private String statusOf(long businessId) {
		return jdbc.queryForObject("SELECT status FROM seller_business WHERE id = ?", String.class, businessId);
	}

	private int auditCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
	}

	// ---------------------------------------------------------------- 승인

	@Test
	@DisplayName("승인 대기 사업체를 승인하면 상태, 처리한 관리자, 처리 시각이 기록되고 감사 로그가 한 줄 남는다")
	void approvePending() throws Exception {
		long businessId = insertBusiness("PENDING");

		approve(businessId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.businessId").value(businessId))
			.andExpect(jsonPath("$.data.status").value("APPROVED"))
			.andExpect(jsonPath("$.data.reviewedAt").isNotEmpty());

		Map<String, Object> business = jdbc.queryForMap("SELECT * FROM seller_business WHERE id = ?", businessId);
		assertThat(business.get("status")).isEqualTo("APPROVED");
		assertThat(business.get("reviewed_by")).isEqualTo(ADMIN_ID);
		assertThat(business.get("reviewed_at")).isNotNull();
		assertThat(business.get("reject_reason")).isNull();

		assertThat(auditCount()).isEqualTo(1);
		Map<String, Object> audit = jdbc.queryForMap("""
			SELECT actor_type, actor_id, business_id, target_type, target_id, action,
			       before_value ->> 'status' AS before_status, after_value ->> 'status' AS after_status
			FROM audit_log
			""");
		assertThat(audit.get("actor_type")).isEqualTo("ADMIN");
		assertThat(audit.get("actor_id")).isEqualTo(ADMIN_ID);
		// 판매자 이력 화면에 관리자 처리 로그가 섞이지 않도록 사업체 번호는 비운다
		assertThat(audit.get("business_id")).isNull();
		assertThat(audit.get("target_type")).isEqualTo("SELLER_BUSINESS");
		assertThat(audit.get("target_id")).isEqualTo(businessId);
		assertThat(audit.get("action")).isEqualTo("APPROVE");
		assertThat(audit.get("before_status")).isEqualTo("PENDING");
		assertThat(audit.get("after_status")).isEqualTo("APPROVED");
	}

	// ---------------------------------------------------------------- 이미 처리된 건, 없는 건

	@Test
	@DisplayName("이미 승인·반려·정지된 사업체는 409 SELLER007 이고, 상태와 감사 로그는 그대로다")
	void alreadyReviewedIsConflict() throws Exception {
		String[] reviewedStatuses = {"APPROVED", "REJECTED", "SUSPENDED"};
		for (int i = 0; i < reviewedStatuses.length; i++) {
			String reviewed = reviewedStatuses[i];
			// 사업자번호는 중복될 수 없어서 상태마다 다르게 넣는다
			long businessId = insertBusinessWithNumber(reviewed, "100000000" + i);

			approve(businessId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("SELLER007"));

			assertThat(statusOf(businessId)).isEqualTo(reviewed);
		}
		assertThat(auditCount()).isZero();
	}

	@Test
	@DisplayName("같은 사업체를 두 번 승인하면 두 번째는 409 이고 감사 로그는 한 줄뿐이다")
	void approveTwice() throws Exception {
		long businessId = insertBusiness("PENDING");

		approve(businessId).andExpect(status().isOk());
		approve(businessId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER007"));

		assertThat(auditCount()).isEqualTo(1);
	}

	// ---------------------------------------------------------------- 권한

	private long insertBusinessWithNumber(String status, String businessNumber) {
		// 반려 상태는 사유 없이 저장되지 않아서(ck_seller_business_reject_reason) 반려일 때만 사유를 넣는다
		Timestamp time = Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 10, 0));
		return jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone,
			                             contact_email, terms_agreed_at, status, reject_reason, created_at, updated_at)
			VALUES ('가상기획', '김대표', ?, '0212345678', 'contact@company.com', ?, ?,
			        CASE WHEN ? = 'REJECTED' THEN '기존 반려 사유' END, ?, ?)
			RETURNING id
			""", Long.class, businessNumber, time, status, status, time, time);
	}
}
