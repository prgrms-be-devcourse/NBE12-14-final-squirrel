package com.squirrel.tickting.domain.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 판매자의 공연장 목록(SLR-01).
 * 공연장은 로컬 시드가 이미 들어 있고 다른 테스트(SeedDataTest)가 그 시드를 믿는다. 그래서 공연장 테이블을 비우지 않고,
 * 이 테스트가 넣은 공연장만 지운다. 개수·순서를 정확히 확인하는 테스트는 시드에 없는 지역(TEST_REGION)으로 걸러서 본다.
 * 계정 종류·역할별 경로 규칙(담당자 허용, 관리자 차단 등)은 SecurityConfigTest 가 확인하므로 여기서는 이 API 가 막혀 있는지만 본다.
 * 검색어 변환(LikeUtil)의 세부 규칙도 LikeUtilTest 가 확인하므로 여기서는 쿼리와 연결된 것만 본다.
 */
class SellerVenueListApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/venues";
	/** 시드 공연장과 섞이지 않게 이 테스트에서만 쓰는 지역 */
	private static final String TEST_REGION = "테스트지역";

	private final List<Long> insertedVenueIds = new ArrayList<>();

	@AfterEach
	void deleteInsertedVenues() {
		for (Long id : insertedVenueIds) {
			jdbc.update("DELETE FROM venue WHERE id = ?", id);
		}
		insertedVenueIds.clear();
	}

	/** 판매자는 사업체 1번 소속으로 만든다. 예매자는 role 과 사업체 번호가 없다. */
	private String token(AccountType type, String role) {
		Long businessId = type == AccountType.SELLER ? 1L : null;
		return "Bearer " + jwtProvider.createAccessToken(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 판매자 대표 토큰으로 보내는 목록 요청 */
	private MockHttpServletRequestBuilder listRequest() {
		return get(URL).header("Authorization", token(AccountType.SELLER, "OWNER"));
	}

	/** 시드 공연장과 섞이지 않게 테스트 지역으로 거른 목록 요청 */
	private MockHttpServletRequestBuilder regionRequest() {
		return listRequest().param("region", TEST_REGION);
	}

	private Long insertVenue(String name, String address, String region, String status, String layoutKey) {
		Long id = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, layout_key, status, created_at, updated_at)
			VALUES (?, ?, ?, 100, ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, name, address, region, layoutKey, status);
		insertedVenueIds.add(id);
		return id;
	}

	private Long insertVenue(String name, String region) {
		return insertVenue(name, "주소 " + name, region, "ACTIVE", null);
	}

	/** 이름이 "공연장00", "공연장01" ... 처럼 이름순과 번호순이 같은 공연장을 count 개 넣는다. */
	private void insertTestRegionVenues(int count) {
		for (int i = 0; i < count; i++) {
			insertVenue("공연장" + String.format("%02d", i), TEST_REGION);
		}
	}

	private List<String> names(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].name");
	}

	private List<String> namesOf(MockHttpServletRequestBuilder request) throws Exception {
		return names(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
	}

	// ---------------------------------------------------------------- 권한, 응답 모양

	@Test
	@DisplayName("예매자 토큰은 403, 토큰이 없으면 401이다")
	void onlySellerCanList() throws Exception {
		mockMvc.perform(get(URL).header("Authorization", token(AccountType.USER, null)))
			.andExpect(status().isForbidden());
		mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("항목에는 공연장을 고르는 데 필요한 값만 있고, 도면 키가 없으면 null이다")
	void summaryHasOnlyListFields() throws Exception {
		Long arenaId = insertVenue("가상 홀", "서울 어딘가", TEST_REGION, "ACTIVE", "sample-arena");
		insertVenue("나중 홀", "서울 어딘가", TEST_REGION, "ACTIVE", null);

		MvcResult result = mockMvc.perform(regionRequest()).andExpect(status().isOk()).andReturn();
		Map<String, Object> first = JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[0]");

		assertThat(first.keySet()).containsExactlyInAnyOrder("venueId", "name", "address", "region", "totalSeats",
			"layoutKey");
		assertThat(((Number) first.get("venueId")).longValue()).isEqualTo(arenaId);
		assertThat(first.get("layoutKey")).isEqualTo("sample-arena");
		assertThat(JsonPath.<Object>read(result.getResponse().getContentAsString(), "$.data.content[1].layoutKey"))
			.isNull();
	}

	@Test
	@DisplayName("사용 중지(INACTIVE) 공연장은 목록에서 빠진다")
	void inactiveVenueIsExcluded() throws Exception {
		insertVenue("사용 중", "주소", TEST_REGION, "ACTIVE", null);
		insertVenue("사용 중지", "주소", TEST_REGION, "INACTIVE", null);

		assertThat(namesOf(regionRequest())).containsExactly("사용 중");
	}

	// ---------------------------------------------------------------- 지역 필터

	@Test
	@DisplayName("지역은 정확히 같은 것만 나오고, 일부만 맞는 지역은 빈 목록이다")
	void regionIsExactMatch() throws Exception {
		insertVenue("서울 홀", "서울");
		insertVenue("서울시 홀", "서울시");

		assertThat(namesOf(listRequest().param("region", "서울"))).contains("서울 홀").doesNotContain("서울시 홀");
		assertThat(namesOf(listRequest().param("region", "서"))).isEmpty();
	}

	@Test
	@DisplayName("지역을 주지 않으면 모든 지역이 나오고, 지역 앞뒤 공백은 무시한다")
	void omittedRegionMeansAll() throws Exception {
		insertVenue("경기 홀", "경기");
		insertVenue("부산 홀", "부산");

		assertThat(namesOf(listRequest())).contains("경기 홀", "부산 홀");
		assertThat(namesOf(listRequest().param("region", " 경기 "))).containsExactly("경기 홀");
	}

	// ---------------------------------------------------------------- 이름 검색

	@Test
	@DisplayName("검색어는 이름에서 부분 일치로 찾고 영문은 대소문자를 가리지 않으며, 주소는 찾지 않는다")
	void keywordSearchesNameOnly() throws Exception {
		insertVenue("가상 아레나 Hall", "서울 강남", TEST_REGION, "ACTIVE", null);
		insertVenue("다른 극장", "서울 아레나로 1", TEST_REGION, "ACTIVE", null);

		assertThat(namesOf(regionRequest().param("keyword", "아레나"))).containsExactly("가상 아레나 Hall");
		assertThat(namesOf(regionRequest().param("keyword", "HALL"))).containsExactly("가상 아레나 Hall");
		assertThat(namesOf(regionRequest().param("keyword", "강남"))).isEmpty();
	}

	@Test
	@DisplayName("검색어의 % 는 전체 검색이 아니라 글자 그대로 찾는다 (쿼리의 ESCAPE 연결 확인)")
	void wildcardCharacterIsLiteral() throws Exception {
		insertVenue("가상 홀", TEST_REGION);
		insertVenue("100%홀", TEST_REGION);

		assertThat(namesOf(regionRequest().param("keyword", "%"))).containsExactly("100%홀");
	}

	@Test
	@DisplayName("지역과 검색어를 함께 주면 둘 다 만족하는 것만 나온다")
	void regionAndKeywordTogether() throws Exception {
		insertVenue("가상 서울홀", "서울");
		insertVenue("가상 경기홀", "경기");

		assertThat(namesOf(listRequest().param("region", "서울").param("keyword", "가상 서울")))
			.containsExactly("가상 서울홀");
		assertThat(namesOf(listRequest().param("region", "경기").param("keyword", "가상 서울"))).isEmpty();
	}

	// ---------------------------------------------------------------- 정렬

	@Test
	@DisplayName("이름순으로 나오고, 이름이 같으면 번호순이다")
	void sortedByNameThenId() throws Exception {
		insertVenue("다 홀", TEST_REGION);
		Long firstSame = insertVenue("나 홀", TEST_REGION);
		insertVenue("가 홀", TEST_REGION);
		Long secondSame = insertVenue("나 홀", TEST_REGION);

		MvcResult result = mockMvc.perform(regionRequest()).andReturn();
		List<Integer> ids = JsonPath.read(result.getResponse().getContentAsString(), "$.data.content[*].venueId");

		assertThat(names(result)).containsExactly("가 홀", "나 홀", "나 홀", "다 홀");
		assertThat(ids.get(1).longValue()).isEqualTo(firstSame);
		assertThat(ids.get(2).longValue()).isEqualTo(secondSame);
	}

	@Test
	@DisplayName("정렬(sort) 파라미터를 보내도 무시하고 이름순을 지킨다")
	void clientSortIsIgnored() throws Exception {
		insertVenue("가 홀", TEST_REGION);
		insertVenue("나 홀", TEST_REGION);

		assertThat(namesOf(regionRequest().param("sort", "name,desc"))).containsExactly("가 홀", "나 홀");
	}

	// ---------------------------------------------------------------- 페이징 경계

	@Test
	@DisplayName("크기를 주지 않으면 20개씩 나오고, 21개면 2페이지에 1개가 남는다")
	void defaultPageSizeIsTwenty() throws Exception {
		insertTestRegionVenues(21);

		mockMvc.perform(regionRequest())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(21))
			.andExpect(jsonPath("$.data.totalPages").value(2));
		mockMvc.perform(regionRequest().param("page", "1"))
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].name").value("공연장20"));
	}

	@Test
	@DisplayName("정확히 20개이면 전체 페이지 수는 1이다")
	void exactlyTwentyIsOnePage() throws Exception {
		insertTestRegionVenues(20);

		mockMvc.perform(regionRequest())
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.totalElements").value(20))
			.andExpect(jsonPath("$.data.totalPages").value(1));
	}

	@Test
	@DisplayName("크기는 최대 20개로 줄어든다. 20은 그대로, 21과 공통 최대(50)를 넘는 100은 20이다")
	void pageSizeIsCappedAtTwenty() throws Exception {
		insertTestRegionVenues(25);

		mockMvc.perform(regionRequest().param("size", "20"))
			.andExpect(jsonPath("$.data.size").value(20));
		mockMvc.perform(regionRequest().param("size", "21"))
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.size").value(20));
		mockMvc.perform(regionRequest().param("size", "100"))
			.andExpect(jsonPath("$.data.content.length()").value(20))
			.andExpect(jsonPath("$.data.size").value(20));
	}

	@Test
	@DisplayName("마지막 페이지 다음은 에러가 아니라 빈 목록이고 전체 개수는 그대로다")
	void pageBeyondLastIsEmpty() throws Exception {
		insertTestRegionVenues(25);

		mockMvc.perform(regionRequest().param("page", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(0))
			.andExpect(jsonPath("$.data.totalElements").value(25))
			.andExpect(jsonPath("$.data.totalPages").value(2));
	}

	@Test
	@DisplayName("크기 0과 음수 페이지는 에러(500)가 아니라 기본값(크기 20, 첫 페이지)으로 처리된다")
	void invalidPagingFallsBackToDefault() throws Exception {
		insertTestRegionVenues(25);

		mockMvc.perform(regionRequest().param("size", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(20));
		mockMvc.perform(regionRequest().param("page", "-1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(0));
	}
}
