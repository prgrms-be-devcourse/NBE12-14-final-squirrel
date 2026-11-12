package com.squirrel.tickting.domain.seller;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 관리자 사업체 목록·검색. 사업체는 가입 API 대신 DB 에 직접 넣어서 상태와 신청 시각을 정한다. */
class AdminSellerBusinessListApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/admin/seller-businesses";
	private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 10, 0);

	private String adminToken() {
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, AccountType.ADMIN, null, null));
	}

	private MockHttpServletRequestBuilder listRequest() {
		return get(URL).header("Authorization", adminToken());
	}

	private void insertBusiness(String company, String representative, String businessNumber, String status,
		LocalDateTime createdAt) {
		Timestamp time = Timestamp.valueOf(createdAt);
		jdbc.update("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone,
			                             contact_email, terms_agreed_at, status, created_at, updated_at)
			VALUES (?, ?, ?, '01011112222', 'contact@company.com', ?, ?, ?, ?)
			""", company, representative, businessNumber, time, status, time, time);
	}

	private List<String> companyNames(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].companyName");
	}

	// ---------------------------------------------------------------- 기본 동작

	@Test
	@DisplayName("상태를 주지 않으면 승인 대기 사업체만, 최신 신청이 먼저 나온다")
	void defaultIsPendingNewestFirst() throws Exception {
		insertBusiness("오래된 신청", "김대표", "1000000001", "PENDING", BASE);
		insertBusiness("최근 신청", "이대표", "1000000002", "PENDING", BASE.plusDays(2));
		insertBusiness("중간 신청", "박대표", "1000000003", "PENDING", BASE.plusDays(1));
		insertBusiness("이미 승인", "최대표", "1000000004", "APPROVED", BASE.plusDays(3));

		MvcResult result = mockMvc.perform(listRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andReturn();

		assertThat(companyNames(result)).containsExactly("최근 신청", "중간 신청", "오래된 신청");
	}

	@Test
	@DisplayName("상태로 거를 수 있고, ALL 이면 모든 상태가 최신순으로 나온다")
	void filterByStatus() throws Exception {
		insertBusiness("대기", "김대표", "1000000001", "PENDING", BASE);
		insertBusiness("승인", "이대표", "1000000002", "APPROVED", BASE.plusDays(1));
		insertBusiness("반려", "박대표", "1000000003", "REJECTED", BASE.plusDays(2));
		insertBusiness("정지", "최대표", "1000000004", "SUSPENDED", BASE.plusDays(3));

		assertThat(companyNames(mockMvc.perform(listRequest().param("status", "APPROVED")).andReturn()))
			.containsExactly("승인");
		assertThat(companyNames(mockMvc.perform(listRequest().param("status", "REJECTED")).andReturn()))
			.containsExactly("반려");
		assertThat(companyNames(mockMvc.perform(listRequest().param("status", "ALL")).andReturn()))
			.containsExactly("정지", "반려", "승인", "대기");
	}

	@Test
	@DisplayName("목록 항목에는 고르는 데 필요한 값만 있고, 연락처·계정·인증 값은 없다")
	void summaryHasOnlyListFields() throws Exception {
		insertBusiness("가상기획", "김대표", "1234567890", "PENDING", BASE);

		MvcResult result = mockMvc.perform(listRequest()).andExpect(status().isOk()).andReturn();

		Map<String, Object> item = JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[0]");
		assertThat(item.keySet()).containsExactlyInAnyOrder("businessId", "companyName", "representativeName",
			"businessNumber", "status", "createdAt", "reviewedAt");
		assertThat(item.get("businessNumber")).isEqualTo("1234567890");
		assertThat(item.get("status")).isEqualTo("PENDING");
		assertThat(item.get("reviewedAt")).isNull();
	}

	// ---------------------------------------------------------------- 검색

	@Test
	@DisplayName("검색어는 상호, 대표자명, 사업자번호에서 부분 일치로 찾고 영문은 대소문자를 가리지 않는다")
	void keywordSearchesThreeFields() throws Exception {
		insertBusiness("가상기획", "김대표", "1111111111", "PENDING", BASE);
		insertBusiness("(주)한빛 Acme", "이사장", "2222222222", "PENDING", BASE.plusMinutes(1));
		insertBusiness("다른회사", "박대표", "3333333333", "PENDING", BASE.plusMinutes(2));

		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "기획")).andReturn()))
			.containsExactly("가상기획");
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "대표")).andReturn()))
			.containsExactly("다른회사", "가상기획");
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "acme")).andReturn()))
			.containsExactly("(주)한빛 Acme");
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "ACME")).andReturn()))
			.containsExactly("(주)한빛 Acme");
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "2222")).andReturn()))
			.containsExactly("(주)한빛 Acme");
	}

	@Test
	@DisplayName("검색어가 비어 있거나 공백이면 전체가 나오고, 앞뒤 공백은 무시한다")
	void blankKeywordMeansAll() throws Exception {
		insertBusiness("가상기획", "김대표", "1111111111", "PENDING", BASE);
		insertBusiness("다른회사", "박대표", "3333333333", "PENDING", BASE.plusMinutes(1));

		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "")).andReturn())).hasSize(2);
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "   ")).andReturn())).hasSize(2);
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", " 기획 ")).andReturn()))
			.containsExactly("가상기획");
	}

	@Test
	@DisplayName("검색어의 %, _ 는 전체 검색이 아니라 글자 그대로 찾는다")
	void wildcardCharactersAreLiteral() throws Exception {
		insertBusiness("가상기획", "김대표", "1111111111", "PENDING", BASE);
		insertBusiness("100%기획", "이대표", "2222222222", "PENDING", BASE.plusMinutes(1));
		insertBusiness("a_b회사", "박대표", "3333333333", "PENDING", BASE.plusMinutes(2));

		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "%")).andReturn()))
			.containsExactly("100%기획");
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "_")).andReturn()))
			.containsExactly("a_b회사");
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "!")).andReturn())).isEmpty();
	}

	@Test
	@DisplayName("검색어와 상태를 함께 주면 둘 다 만족하는 것만 나온다")
	void keywordAndStatusTogether() throws Exception {
		insertBusiness("가상기획", "김대표", "1111111111", "PENDING", BASE);
		insertBusiness("가상기획2", "이대표", "2222222222", "APPROVED", BASE.plusMinutes(1));

		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "가상").param("status", "APPROVED"))
			.andReturn())).containsExactly("가상기획2");
		assertThat(companyNames(mockMvc.perform(listRequest().param("keyword", "가상").param("status", "REJECTED"))
			.andReturn())).isEmpty();
	}

	// ---------------------------------------------------------------- 페이징

	private void insertPending(int count) {
		for (int i = 0; i < count; i++) {
			insertBusiness("회사" + String.format("%02d", i), "대표", String.format("%010d", i), "PENDING",
				BASE.plusMinutes(i));
		}
	}

	@Test
	@DisplayName("크기를 주지 않으면 10개씩 나오고, 전체 개수와 전체 페이지 수가 맞다")
	void defaultPageSizeIsTen() throws Exception {
		insertPending(25);

		mockMvc.perform(listRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(10))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10))
			.andExpect(jsonPath("$.data.totalElements").value(25))
			.andExpect(jsonPath("$.data.totalPages").value(3));
		mockMvc.perform(listRequest().param("page", "2"))
			.andExpect(jsonPath("$.data.content.length()").value(5))
			.andExpect(jsonPath("$.data.page").value(2));
	}

	@Test
	@DisplayName("크기는 최대 20개로 줄어들고, 범위를 벗어난 페이지는 빈 목록이다")
	void pageSizeIsCappedAtTwenty() throws Exception {
		insertPending(25);

		mockMvc.perform(listRequest().param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalPages").value(2));
		mockMvc.perform(listRequest().param("size", "5").param("page", "1"))
			.andExpect(jsonPath("$.data.content.length()").value(5))
			.andExpect(jsonPath("$.data.size").value(5));
		mockMvc.perform(listRequest().param("page", "99"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(0))
			.andExpect(jsonPath("$.data.totalElements").value(25));
	}

	@Test
	@DisplayName("클라이언트가 보낸 정렬은 무시하고 항상 최신순이다")
	void clientSortIsIgnored() throws Exception {
		insertBusiness("가나다", "김대표", "1000000001", "PENDING", BASE);
		insertBusiness("마바사", "이대표", "1000000002", "PENDING", BASE.plusDays(1));

		assertThat(companyNames(mockMvc.perform(listRequest().param("sort", "companyName,asc")).andReturn()))
			.containsExactly("마바사", "가나다");
		assertThat(companyNames(mockMvc.perform(listRequest().param("sort", "noSuchField,desc")).andReturn()))
			.containsExactly("마바사", "가나다");
	}

	@Test
	@DisplayName("결과가 없어도 200 이고 빈 목록과 0 이 내려온다")
	void emptyResult() throws Exception {
		mockMvc.perform(listRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(0))
			.andExpect(jsonPath("$.data.totalElements").value(0))
			.andExpect(jsonPath("$.data.totalPages").value(0));
	}

	@Test
	@DisplayName("알 수 없는 상태 값은 400 COMMON000 이다")
	void unknownStatusIs400() throws Exception {
		mockMvc.perform(listRequest().param("status", "UNKNOWN"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}

	// ---------------------------------------------------------------- 권한

	@Test
	@DisplayName("토큰이 없으면 401, 예매자·판매자 토큰이면 403, 관리자만 볼 수 있다")
	void onlyAdminCanList() throws Exception {
		mockMvc.perform(get(URL))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH200"));

		for (CustomUserDetails notAdmin : Set.of(
			new CustomUserDetails(1L, AccountType.USER, null, null),
			new CustomUserDetails(1L, AccountType.SELLER, "OWNER", 1L),
			new CustomUserDetails(2L, AccountType.SELLER, "STAFF", 1L))) {
			mockMvc.perform(get(URL).header("Authorization", "Bearer " + jwtProvider.createAccessToken(notAdmin)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH100"));
		}
	}
}
