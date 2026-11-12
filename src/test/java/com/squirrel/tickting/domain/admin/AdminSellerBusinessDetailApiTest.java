package com.squirrel.tickting.domain.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MvcResult;

/** 관리자 사업체 상세. 사업체와 계정은 가입 API 대신 DB 에 직접 넣어서 상태와 값을 정한다. */
class AdminSellerBusinessDetailApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/admin/seller-businesses/{id}";
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 10, 0);

	private String adminToken() {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.ADMIN, null, null));
	}

	private long insertBusiness(String status, String rejectReason, Long reviewedBy, LocalDateTime reviewedAt) {
		Timestamp time = Timestamp.valueOf(BASE);
		Timestamp reviewed = reviewedAt == null ? null : Timestamp.valueOf(reviewedAt);
		return jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone,
			                             contact_email, terms_agreed_at, status, reject_reason, reviewed_by,
			                             reviewed_at, created_at, updated_at)
			VALUES ('가상기획', '김대표', '1234567890', '0212345678', 'contact@company.com', ?, ?, ?, ?, ?, ?, ?)
			RETURNING id
			""", Long.class, time, status, rejectReason, reviewedBy, reviewed, time, time);
	}

	private long insertMember(long businessId, String email, String phone, String role, String ciHash,
		boolean deleted) {
		Timestamp time = Timestamp.valueOf(BASE);
		return jdbc.queryForObject("""
			INSERT INTO seller_member (business_id, email, password, name, phone, role, ci_hash, status,
			                           created_at, updated_at, deleted_at)
			VALUES (?, ?, 'hashed-password', '김대표', ?, ?, ?, 'ACTIVE', ?, ?, ?)
			RETURNING id
			""", Long.class, businessId, email, phone, role, ciHash, time, time, deleted ? time : null);
	}

	@Test
	@DisplayName("승인 대기 사업체 상세에 사업체 정보와 신청한 대표 계정이 함께 나온다")
	void pendingDetailWithOwner() throws Exception {
		long businessId = insertBusiness("PENDING", null, null, null);
		long ownerId = insertMember(businessId, "owner@company.com", "01011112222", "OWNER", "ci-owner", false);

		mockMvc.perform(get(URL, businessId).header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.businessId").value(businessId))
			.andExpect(jsonPath("$.data.companyName").value("가상기획"))
			.andExpect(jsonPath("$.data.representativeName").value("김대표"))
			.andExpect(jsonPath("$.data.businessNumber").value("1234567890"))
			.andExpect(jsonPath("$.data.contactPhone").value("0212345678"))
			.andExpect(jsonPath("$.data.contactEmail").value("contact@company.com"))
			.andExpect(jsonPath("$.data.status").value("PENDING"))
			.andExpect(jsonPath("$.data.rejectReason").doesNotExist())
			.andExpect(jsonPath("$.data.reviewedBy").doesNotExist())
			.andExpect(jsonPath("$.data.reviewedAt").doesNotExist())
			.andExpect(jsonPath("$.data.owner.memberId").value(ownerId))
			.andExpect(jsonPath("$.data.owner.email").value("owner@company.com"))
			.andExpect(jsonPath("$.data.owner.phone").value("01011112222"))
			.andExpect(jsonPath("$.data.owner.status").value("ACTIVE"));
	}

	@Test
	@DisplayName("반려된 사업체는 반려 사유와 처리한 관리자 번호, 처리 시각이 나온다")
	void rejectedDetailHasReason() throws Exception {
		long businessId = insertBusiness("REJECTED", "사업자등록증이 흐립니다.", 7L, BASE.plusDays(1));
		insertMember(businessId, "owner@company.com", "01011112222", "OWNER", "ci-owner", false);

		mockMvc.perform(get(URL, businessId).header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("REJECTED"))
			.andExpect(jsonPath("$.data.rejectReason").value("사업자등록증이 흐립니다."))
			.andExpect(jsonPath("$.data.reviewedBy").value(7))
			.andExpect(jsonPath("$.data.reviewedAt").exists());
	}

	@Test
	@DisplayName("대표 계정에는 번호·이메일·휴대폰·상태만 있고 비밀번호·CI·이름은 없다")
	void ownerHasOnlySafeFields() throws Exception {
		long businessId = insertBusiness("PENDING", null, null, null);
		insertMember(businessId, "owner@company.com", "01011112222", "OWNER", "ci-owner", false);

		MvcResult result = mockMvc.perform(get(URL, businessId).header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andReturn();

		String body = result.getResponse().getContentAsString();
		Map<String, Object> owner = JsonPath.read(body, "$.data.owner");
		assertThat(owner.keySet()).containsExactlyInAnyOrder("memberId", "email", "phone", "status");
		assertThat(body).doesNotContain("hashed-password").doesNotContain("ci-owner");
	}

	@Test
	@DisplayName("담당자(STAFF)가 있어도 대표(OWNER) 계정만 나온다")
	void onlyOwnerIsShown() throws Exception {
		long businessId = insertBusiness("APPROVED", null, 1L, BASE.plusDays(1));
		long ownerId = insertMember(businessId, "owner@company.com", "01011112222", "OWNER", "ci-owner", false);
		insertMember(businessId, "staff@company.com", "01033334444", "STAFF", "ci-staff", false);

		mockMvc.perform(get(URL, businessId).header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.owner.memberId").value(ownerId))
			.andExpect(jsonPath("$.data.owner.email").value("owner@company.com"));
	}

	@Test
	@DisplayName("탈퇴 처리된 대표 계정은 나오지 않고, 상세는 owner 가 null 로 내려간다")
	void deletedOwnerIsNotShown() throws Exception {
		long businessId = insertBusiness("SUSPENDED", null, null, null);
		insertMember(businessId, "owner@company.com", "01011112222", "OWNER", "ci-owner", true);

		mockMvc.perform(get(URL, businessId).header("Authorization", adminToken()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.businessId").value(businessId))
			.andExpect(jsonPath("$.data.owner").doesNotExist());
	}

	@Test
	@DisplayName("없는 사업체는 404 와 SELLER006 으로 답한다")
	void notFound() throws Exception {
		mockMvc.perform(get(URL, 999999L).header("Authorization", adminToken()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("SELLER006"));
	}

	@Test
	@DisplayName("사업체 번호가 숫자가 아니면 400 으로 답한다")
	void invalidIdIsBadRequest() throws Exception {
		mockMvc.perform(get(URL, "abc").header("Authorization", adminToken()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}

	@Test
	@DisplayName("토큰이 없으면 401, 예매자·판매자 토큰이면 403, 관리자만 볼 수 있다")
	void onlyAdminCanView() throws Exception {
		long businessId = insertBusiness("PENDING", null, null, null);
		insertMember(businessId, "owner@company.com", "01011112222", "OWNER", "ci-owner", false);

		mockMvc.perform(get(URL, businessId))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));

		// 판매자 대표가 자기 사업체 번호로 호출해도 관리자 API 는 열리지 않는다
		for (CustomUserDetails notAdmin : Set.of(
			new CustomUserDetails(1L, AccountType.USER, null, null),
			new CustomUserDetails(1L, AccountType.SELLER, "OWNER", businessId),
			new CustomUserDetails(2L, AccountType.SELLER, "STAFF", businessId))) {
			mockMvc.perform(get(URL, businessId)
					.header("Authorization", "Bearer " + jwtProvider.createAccessToken(notAdmin)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH100"));
		}
	}
}
