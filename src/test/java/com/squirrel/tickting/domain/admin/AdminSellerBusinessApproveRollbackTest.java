package com.squirrel.tickting.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.audit.infra.repository.AuditLogRepository;
import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

/** 감사 로그를 남기다 실패하면 승인도 함께 취소되는지 본다. 감사 로그 서비스만 실패하도록 바꿔 둔 별도 클래스다. */
@Import(AdminSellerBusinessApproveRollbackTest.FailingAuditConfig.class)
class AdminSellerBusinessApproveRollbackTest extends IntegrationTestSupport {

	/** 기록을 항상 실패시키는 감사 로그 서비스로 바꿔 끼운다. (Mockito 없이 실패 상황을 만든다) */
	@TestConfiguration(proxyBeanMethods = false)
	static class FailingAuditConfig {

		@Bean
		@Primary
		AuditLogService failingAuditLogService(AuditLogRepository repository, ObjectMapper objectMapper) {
			return new AuditLogService(repository, objectMapper) {
				@Override
				public void saveHistory(AuditLogCommand command) {
					throw new IllegalStateException("로그 저장 실패");
				}
			};
		}
	}

	@Test
	@DisplayName("감사 로그 기록이 실패하면 승인도 취소되어 사업체는 승인 대기로 남는다")
	void auditFailureRollsBackApproval() throws Exception {
		Timestamp time = Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 10, 0));
		long businessId = jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone,
			                             contact_email, terms_agreed_at, status, created_at, updated_at)
			VALUES ('가상기획', '김대표', '1234567890', '0212345678', 'contact@company.com', ?, 'PENDING', ?, ?)
			RETURNING id
			""", Long.class, time, time, time);
		String token = "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(7L, AccountType.ADMIN, null, null));

		mockMvc.perform(post("/api/v1/admin/seller-businesses/{id}/approve", businessId).header("Authorization", token))
			.andExpect(status().isInternalServerError());

		Map<String, Object> business = jdbc.queryForMap(
			"SELECT status, reviewed_by, reviewed_at FROM seller_business WHERE id = ?", businessId);
		assertThat(business.get("status")).isEqualTo("PENDING");
		assertThat(business.get("reviewed_by")).isNull();
		assertThat(business.get("reviewed_at")).isNull();
	}
}
