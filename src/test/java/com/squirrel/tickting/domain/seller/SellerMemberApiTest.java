package com.squirrel.tickting.domain.seller;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 담당자 목록·비활성화·복구 (AUTH-26~28)와 비활성화된 계정의 남은 세션 차단.
 * 시드 사업체를 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업자번호로 승인된 사업체 두 개(담당자가 옮겨 다니는 A·B)를 넣고,
 * 끝나면 그 사업체들의 계정·사업체만 지운다.
 */
class SellerMemberApiTest extends IntegrationTestSupport {

	private static final String MEMBERS_URL = "/api/v1/seller/members";
	private static final String STAFF_EMAIL = "staff-member@example.com";

	@Autowired
	private PasswordEncoder passwordEncoder;

	private Long businessA;
	private Long businessB;
	private Long ownerA;

	@BeforeEach
	void insertBusinesses() {
		businessA = insertBusiness("(주)에이회사", "8960000001");
		businessB = insertBusiness("(주)비회사", "8960000002");
		ownerA = insertMember(businessA, "owner-a@example.com", "OWNER", "ACTIVE");
	}

	@AfterEach
	void deleteInsertedRows() {
		for (Long id : List.of(businessA, businessB)) {
			jdbc.update("DELETE FROM seller_member WHERE business_id = ?", id);
			jdbc.update("DELETE FROM seller_business WHERE id = ?", id);
		}
	}

	private Long insertBusiness(String companyName, String businessNumber) {
		return jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone, contact_email,
			                             terms_agreed_at, status, created_at, updated_at)
			VALUES (?, '김대표', ?, '01011112222', 'contact-member@example.com', NOW(), 'APPROVED', NOW(), NOW())
			RETURNING id
			""", Long.class, companyName, businessNumber);
	}

	/** 비밀번호는 PASSWORD 라서 실제 로그인 API 로 로그인할 수 있다. */
	private Long insertMember(Long business, String email, String role, String status) {
		return jdbc.queryForObject("""
			INSERT INTO seller_member (business_id, email, password, name, phone, role, status, created_at, updated_at)
			VALUES (?, ?, ?, '테스트', '01033334444', ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, business, email, passwordEncoder.encode(PASSWORD), role, status);
	}

	private Cookie ownerA() {
		return sessionCookie(new CustomUserDetails(ownerA, AccountType.SELLER, "OWNER", businessA));
	}

	private ResultActions deactivate(Cookie session, Long memberId) throws Exception {
		return mockMvc.perform(post(MEMBERS_URL + "/{memberId}/deactivate", memberId).cookie(session));
	}

	private ResultActions reactivate(Cookie session, Long memberId) throws Exception {
		return mockMvc.perform(post(MEMBERS_URL + "/{memberId}/reactivate", memberId).cookie(session));
	}

	private MvcResult login(String email) throws Exception {
		return mockMvc.perform(post("/api/v2/auth/sellers/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
			.andReturn();
	}

	private String statusOf(Long memberId) {
		return jdbc.queryForObject("SELECT status FROM seller_member WHERE id = ?", String.class, memberId);
	}

	@Test
	@DisplayName("비활성화하면 그 담당자가 쓰던 세션도 다음 요청부터 401 AUTH003 이고 세션이 지워지며, 새로 로그인할 수도 없다")
	void deactivationBlocksExistingSession() throws Exception {
		Long staffId = insertMember(businessA, STAFF_EMAIL, "STAFF", "ACTIVE");
		Cookie staffSession = sessionCookie(login(STAFF_EMAIL));
		mockMvc.perform(get("/api/v1/seller/cancel-policies").cookie(staffSession)).andExpect(status().isOk());

		deactivate(ownerA(), staffId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("INACTIVE"));

		mockMvc.perform(get("/api/v1/seller/cancel-policies").cookie(staffSession))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH003"));
		// 세션이 지워져서 같은 쿠키는 이제 로그인하지 않은 요청이다
		mockMvc.perform(get("/api/v1/seller/cancel-policies").cookie(staffSession))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));
		MvcResult relogin = login(STAFF_EMAIL);
		assertThat(relogin.getResponse().getStatus()).isEqualTo(401);
		assertThat(relogin.getResponse().getContentAsString()).contains("AUTH003");
	}

	@Test
	@DisplayName("A 회사 퇴사 후 B 회사에 들어간 담당자는 B 계정이 활성인 동안 A 가 복구하면 409 SELLER013 이고, B 가 비활성화한 뒤에는 예전 비밀번호로 A 에 복구된다")
	void reactivationWaitsForOtherBusiness() throws Exception {
		Long staffA = insertMember(businessA, STAFF_EMAIL, "STAFF", "INACTIVE");
		Long staffB = insertMember(businessB, STAFF_EMAIL, "STAFF", "ACTIVE");
		Long ownerB = insertMember(businessB, "owner-b@example.com", "OWNER", "ACTIVE");

		reactivate(ownerA(), staffA)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SELLER013"));
		assertThat(statusOf(staffA)).isEqualTo("INACTIVE");

		deactivate(sessionCookie(new CustomUserDetails(ownerB, AccountType.SELLER, "OWNER", businessB)), staffB)
			.andExpect(status().isOk());
		reactivate(ownerA(), staffA)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ACTIVE"));

		assertThat(statusOf(staffA)).isEqualTo("ACTIVE");
		assertThat(login(STAFF_EMAIL).getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("다른 회사 계정은 비활성화·복구 모두 404 SELLER009 이고, 목록에도 나오지 않는다")
	void otherBusinessMemberIsNotFound() throws Exception {
		Long otherStaff = insertMember(businessB, STAFF_EMAIL, "STAFF", "ACTIVE");

		deactivate(ownerA(), otherStaff)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SELLER009"));
		reactivate(ownerA(), otherStaff)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SELLER009"));
		assertThat(statusOf(otherStaff)).isEqualTo("ACTIVE");
		mockMvc.perform(get(MEMBERS_URL).cookie(ownerA()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(1))
			.andExpect(jsonPath("$.data[0].memberId").value(ownerA));
	}

	@Test
	@DisplayName("담당자도 자기 회사 목록은 보지만(다른 회사 계정은 안 보임), 비활성화·복구는 403 AUTH100 이다")
	void staffCanListButCannotManage() throws Exception {
		Long staffId = insertMember(businessA, STAFF_EMAIL, "STAFF", "ACTIVE");
		insertMember(businessB, "staff-other@example.com", "STAFF", "ACTIVE");
		Cookie staff = sessionCookie(new CustomUserDetails(staffId, AccountType.SELLER, "STAFF", businessA));

		mockMvc.perform(get(MEMBERS_URL).cookie(staff))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].memberId").value(ownerA))
			.andExpect(jsonPath("$.data[1].memberId").value(staffId));
		deactivate(staff, staffId).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH100"));
		reactivate(staff, staffId).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH100"));
		assertThat(statusOf(staffId)).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("대표 계정은 비활성화할 수 없고(400 SELLER010), 이미 비활성인 담당자는 409 SELLER011, 이미 활성인 담당자 복구는 409 SELLER012 이다")
	void invalidTransitions() throws Exception {
		Long inactive = insertMember(businessA, STAFF_EMAIL, "STAFF", "INACTIVE");
		Long active = insertMember(businessA, "staff-active@example.com", "STAFF", "ACTIVE");

		deactivate(ownerA(), ownerA)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SELLER010"));
		deactivate(ownerA(), inactive)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER011"));
		reactivate(ownerA(), active)
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SELLER012"));
		assertThat(statusOf(ownerA)).isEqualTo("ACTIVE");
	}
}
