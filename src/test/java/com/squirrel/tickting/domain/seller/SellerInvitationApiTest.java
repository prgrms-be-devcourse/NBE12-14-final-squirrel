package com.squirrel.tickting.domain.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 담당자 초대 (AUTH-23~25).
 * 시드 사업체를 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업자번호로 승인된 사업체와 대표를 넣고,
 * 끝나면 그 사업체의 초대·계정·사업체만 지운다. 메일은 가짜 발송기가 받고, 시계는 고정한다.
 */
class SellerInvitationApiTest extends IntegrationTestSupport {

	private static final String INVITE_URL = "/api/v1/seller/invitations";
	private static final String COMPANY = "(주)초대테스트";
	private static final String STAFF_EMAIL = "staff-invite@example.com";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 10, 0);

	private Long businessId;
	private Long ownerId;

	@BeforeEach
	void insertBusiness() {
		emailClock.setNow(NOW);
		businessId = jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone, contact_email,
			                             terms_agreed_at, status, created_at, updated_at)
			VALUES (?, '김대표', '8970000001', '01011112222', 'contact-invite@example.com', NOW(), 'APPROVED', NOW(), NOW())
			RETURNING id
			""", Long.class, COMPANY);
		ownerId = insertMember("owner-invite@example.com", "OWNER", "ACTIVE");
	}

	@AfterEach
	void deleteInsertedRows() {
		jdbc.update("DELETE FROM seller_invitation WHERE business_id = ?", businessId);
		jdbc.update("DELETE FROM seller_member WHERE business_id = ?", businessId);
		jdbc.update("DELETE FROM seller_business WHERE id = ?", businessId);
	}

	private Long insertMember(String email, String role, String status) {
		return jdbc.queryForObject("""
			INSERT INTO seller_member (business_id, email, password, name, phone, role, status, created_at, updated_at)
			VALUES (?, ?, 'not-used', '테스트', '01033334444', ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, email, role, status);
	}

	private Cookie ownerSession() {
		return sessionCookie(new CustomUserDetails(ownerId, AccountType.SELLER, "OWNER", businessId));
	}

	private ResultActions invite(Cookie session, String email) throws Exception {
		return mockMvc.perform(post(INVITE_URL).cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\": \"" + email + "\"}"));
	}

	private List<Map<String, Object>> invitations(String email) {
		return jdbc.queryForList("SELECT * FROM seller_invitation WHERE business_id = ? AND email = ? ORDER BY id",
			businessId, email);
	}

	private static String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("대표가 초대하면 72시간짜리 초대가 생기고 회사 이름과 링크가 메일로 가며, DB 에는 토큰 원문 대신 해시만 남는다")
	void ownerInvites() throws Exception {
		invite(ownerSession(), "  Staff-Invite@Example.com ")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.email").value(STAFF_EMAIL))
			.andExpect(jsonPath("$.data.expiresAt").value("2026-11-30T10:00:00"));

		String token = fakeEmailSender.lastInvitationToken(STAFF_EMAIL);
		assertThat(token).isNotBlank();
		assertThat(fakeEmailSender.lastInvitationLink(STAFF_EMAIL))
			.isEqualTo("http://localhost:3000/seller/invitation#token=" + token);
		assertThat(fakeEmailSender.lastInvitationCompany(STAFF_EMAIL)).isEqualTo(COMPANY);
		List<Map<String, Object>> rows = invitations(STAFF_EMAIL);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst())
			.containsEntry("invited_by", ownerId)
			.containsEntry("token", sha256(token))
			.containsEntry("expires_at", Timestamp.valueOf(NOW.plusHours(72)))
			.containsEntry("used_at", null);
	}

	@Test
	@DisplayName("담당자 토큰으로 초대하면 403 AUTH100 이고 초대·메일이 생기지 않는다")
	void staffCannotInvite() throws Exception {
		Long staffId = insertMember("staff-existing@example.com", "STAFF", "ACTIVE");

		invite(sessionCookie(new CustomUserDetails(staffId, AccountType.SELLER, "STAFF", businessId)), STAFF_EMAIL)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));

		assertThat(invitations(STAFF_EMAIL)).isEmpty();
		assertThat(fakeEmailSender.lastInvitationLink(STAFF_EMAIL)).isNull();
	}

	@Test
	@DisplayName("이미 판매자 계정이 있는 이메일(비활성 담당자 포함)은 409 SELLER000 이고 초대·메일이 생기지 않는다")
	void existingSellerEmailIsRejected() throws Exception {
		insertMember(STAFF_EMAIL, "STAFF", "INACTIVE");

		invite(ownerSession(), STAFF_EMAIL)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SELLER000"));

		assertThat(invitations(STAFF_EMAIL)).isEmpty();
		assertThat(fakeEmailSender.lastInvitationLink(STAFF_EMAIL)).isNull();
	}

	@Test
	@DisplayName("같은 이메일로 다시 초대하면 이전 초대는 지금 시각으로 만료되고 새 초대만 72시간 유효하다")
	void reinviteExpiresPrevious() throws Exception {
		invite(ownerSession(), STAFF_EMAIL).andExpect(status().isOk());
		emailClock.setNow(NOW.plusHours(1));
		invite(ownerSession(), STAFF_EMAIL).andExpect(status().isOk());

		List<Map<String, Object>> rows = invitations(STAFF_EMAIL);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).containsEntry("expires_at", Timestamp.valueOf(NOW.plusHours(1)));
		assertThat(rows.get(1)).containsEntry("expires_at", Timestamp.valueOf(NOW.plusHours(73)));
	}

	@Test
	@DisplayName("발송 대기가 가득 차면 503 AUTH304 이고 만든 초대는 바로 무효가 된다")
	void queueFullExpiresInvitation() throws Exception {
		emailSendExecutor.setRejecting(true);

		invite(ownerSession(), STAFF_EMAIL)
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AUTH304"));

		List<Map<String, Object>> rows = invitations(STAFF_EMAIL);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst()).containsEntry("expires_at", Timestamp.valueOf(NOW));
	}
}
