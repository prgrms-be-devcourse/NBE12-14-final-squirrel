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
import org.springframework.http.MediaType;
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

	// ---------------------------------------------------------------- 사유 검증

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

	// ---------------------------------------------------------------- 권한
}
