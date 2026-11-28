package com.squirrel.tickting.domain.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 판매자 문의 유형 목록·작성·사업체 목록·상세. 같은 사업체의 대표·담당자가 서로의 문의를 본다. */
class SellerInquiryApiTest extends IntegrationTestSupport {

	private static final String INQUIRIES = "/api/v1/seller/inquiries";
	private static final String IMAGE = "https://cdn.example.com/public/seller_inquiry/seller-1/a.jpg";

	private Long businessA;
	private Long businessB;
	private Long ownerA;
	private Long staffA;
	private Long ownerB;

	@BeforeEach
	void setUpSellers() {
		jdbc.execute("TRUNCATE TABLE inquiry, inquiry_history RESTART IDENTITY");
		businessA = insertBusiness("가상기획", "1111111111");
		businessB = insertBusiness("다른기획", "2222222222");
		ownerA = insertMember(businessA, "owner-a@example.com", "김대표", "OWNER");
		staffA = insertMember(businessA, "staff-a@example.com", "이담당", "STAFF");
		ownerB = insertMember(businessB, "owner-b@example.com", "박대표", "OWNER");
	}

	@AfterEach
	void cleanInquiries() {
		jdbc.execute("TRUNCATE TABLE inquiry, inquiry_history RESTART IDENTITY");
	}

	private Long insertBusiness(String companyName, String businessNumber) {
		return jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone, contact_email,
			                             terms_agreed_at, status, created_at, updated_at)
			VALUES (?, '김대표', ?, '01011112222', 'contact@example.com', NOW(), 'APPROVED', NOW(), NOW())
			RETURNING id
			""", Long.class, companyName, businessNumber);
	}

	/** 이 테스트는 로그인하지 않고 세션을 직접 만들어서 비밀번호는 쓰이지 않는다. 그래도 고정 값을 두지 않고 매번 만든다. */
	private Long insertMember(Long business, String email, String name, String role) {
		return jdbc.queryForObject("""
			INSERT INTO seller_member (business_id, email, password, name, phone, role, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, '01033334444', ?, 'ACTIVE', NOW(), NOW())
			RETURNING id
			""", Long.class, business, email, UUID.randomUUID().toString(), name, role);
	}

	private Cookie session(Long memberId, String role, Long businessId) {
		return sessionCookie(new CustomUserDetails(memberId, AccountType.SELLER, role, businessId));
	}

	private Map<String, Object> body(String category, List<String> imageUrls) {
		Map<String, Object> body = new HashMap<>();
		body.put("category", category);
		body.put("title", " 정산 금액이 예상과 달라요 ");
		body.put("content", "11월 공연 정산 금액이 판매 현황과 다릅니다.");
		body.put("images", imageUrls.stream().map(url -> Map.of("url", url)).toList());
		return body;
	}

	private ResultActions create(Cookie session, Map<String, Object> body) throws Exception {
		return mockMvc.perform(post(INQUIRIES)
			.cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private ResultActions update(Cookie session, Long inquiryId, Map<String, Object> body) throws Exception {
		return mockMvc.perform(put(INQUIRIES + "/" + inquiryId)
			.cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private ResultActions remove(Cookie session, Long inquiryId) throws Exception {
		return mockMvc.perform(delete(INQUIRIES + "/" + inquiryId).cookie(session));
	}

	private Long createdId(Cookie session, Map<String, Object> body) throws Exception {
		MvcResult created = create(session, body).andExpect(status().isCreated()).andReturn();
		return Long.parseLong(read(created, "$.data.inquiryId"));
	}

	private List<String> historyActions(Long inquiryId) {
		return jdbc.queryForList("SELECT action FROM inquiry_history WHERE inquiry_id = ? ORDER BY id", String.class,
			inquiryId);
	}

	private Map<String, Object> changedBody() {
		Map<String, Object> body = new HashMap<>();
		body.put("category", "PERFORMANCE");
		body.put("title", "바꾼 제목");
		body.put("content", "바꾼 내용");
		body.put("images", List.of());
		return body;
	}

	@Test
	@DisplayName("대표가 쓴 문의는 작성자 이름·이메일·사업체가 저장되고 작성 이력이 남으며, 같은 사업체 담당자가 목록·상세에서 본다")
	void sharesInquiriesWithinBusiness() throws Exception {
		MvcResult created = create(session(ownerA, "OWNER", businessA), body("SETTLEMENT", List.of(IMAGE)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("WAITING"))
			.andReturn();
		Long inquiryId = Long.parseLong(read(created, "$.data.inquiryId"));

		Map<String, Object> row = jdbc.queryForMap(
			"SELECT author_type, author_id, business_id, author_name, author_email, booking_id, title FROM inquiry WHERE id = ?",
			inquiryId);
		assertThat(row.get("author_type")).isEqualTo("SELLER");
		assertThat(row.get("author_id")).isEqualTo(ownerA);
		assertThat(row.get("business_id")).isEqualTo(businessA);
		assertThat(row.get("author_name")).isEqualTo("김대표");
		assertThat(row.get("author_email")).isEqualTo("owner-a@example.com");
		assertThat(row.get("booking_id")).isNull();
		assertThat(row.get("title")).isEqualTo("정산 금액이 예상과 달라요");
		assertThat(historyActions(inquiryId)).containsExactly("CREATED");

		Cookie staff = session(staffA, "STAFF", businessA);
		mockMvc.perform(get(INQUIRIES).cookie(staff))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].inquiryId").value(inquiryId))
			.andExpect(jsonPath("$.data.content[0].authorName").value("김대표"));
		mockMvc.perform(get(INQUIRIES + "/" + inquiryId).cookie(staff))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.authorId").value(ownerA))
			.andExpect(jsonPath("$.data.imageUrls[0]").value(IMAGE));
	}

	@Test
	@DisplayName("다른 사업체의 문의는 목록에 보이지 않고 상세는 404 다")
	void hidesOtherBusinessInquiries() throws Exception {
		MvcResult created = create(session(ownerA, "OWNER", businessA), body("ETC", List.of()))
			.andExpect(status().isCreated())
			.andReturn();
		Long inquiryId = Long.parseLong(read(created, "$.data.inquiryId"));
		Cookie other = session(ownerB, "OWNER", businessB);

		mockMvc.perform(get(INQUIRIES).cookie(other))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(0));
		mockMvc.perform(get(INQUIRIES + "/" + inquiryId).cookie(other))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INQUIRY001"));
	}

	@Test
	@DisplayName("판매자는 예매자 유형을 고를 수 없어 400 이고, 유형 목록에도 판매자 유형과 공용 유형만 나온다")
	void rejectsUserCategory() throws Exception {
		Cookie owner = session(ownerA, "OWNER", businessA);

		create(owner, body("CANCEL_REFUND", List.of()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INQUIRY000"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inquiry", Integer.class)).isZero();

		mockMvc.perform(get(INQUIRIES + "/categories").cookie(owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[*].code").value(
				contains("PERFORMANCE", "SETTLEMENT", "BUSINESS", "ETC")));
	}

	@Test
	@DisplayName("작성자 본인은 수정·삭제할 수 있고 이력이 남으며, 같은 사업체의 다른 계정이 수정·삭제하면 404 이고 내용이 그대로다")
	void onlyAuthorEditsOrDeletes() throws Exception {
		Cookie owner = session(ownerA, "OWNER", businessA);
		Cookie staff = session(staffA, "STAFF", businessA);
		Long inquiryId = createdId(owner, body("SETTLEMENT", List.of(IMAGE)));

		update(staff, inquiryId, changedBody())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INQUIRY001"));
		remove(staff, inquiryId)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INQUIRY001"));
		assertThat(jdbc.queryForObject("SELECT title FROM inquiry WHERE id = ?", String.class, inquiryId))
			.isEqualTo("정산 금액이 예상과 달라요");
		assertThat(historyActions(inquiryId)).containsExactly("CREATED");

		update(owner, inquiryId, changedBody())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.title").value("바꾼 제목"))
			.andExpect(jsonPath("$.data.category.code").value("PERFORMANCE"))
			.andExpect(jsonPath("$.data.imageUrls").isEmpty());
		remove(owner, inquiryId).andExpect(status().isOk());

		mockMvc.perform(get(INQUIRIES).cookie(staff))
			.andExpect(jsonPath("$.data.totalElements").value(0));
		assertThat(historyActions(inquiryId)).containsExactly("CREATED", "UPDATED", "DELETED");
	}

	@Test
	@DisplayName("관리자가 답변한 판매자 문의는 작성자도 수정·삭제할 수 없어 409 이고 내용이 그대로다")
	void rejectsEditAfterAnswer() throws Exception {
		Cookie owner = session(ownerA, "OWNER", businessA);
		Long inquiryId = createdId(owner, body("SETTLEMENT", List.of()));
		mockMvc.perform(post("/api/v1/admin/inquiries/" + inquiryId + "/answer")
				.cookie(sessionCookie(new CustomUserDetails(1L, AccountType.ADMIN, null, null)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("answer", "확인 후 안내드리겠습니다."))))
			.andExpect(status().isOk());

		update(owner, inquiryId, changedBody())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INQUIRY002"));
		remove(owner, inquiryId)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INQUIRY002"));
		assertThat(jdbc.queryForObject("SELECT title FROM inquiry WHERE id = ?", String.class, inquiryId))
			.isEqualTo("정산 금액이 예상과 달라요");
		assertThat(historyActions(inquiryId)).containsExactly("CREATED", "ANSWERED");
	}
}
