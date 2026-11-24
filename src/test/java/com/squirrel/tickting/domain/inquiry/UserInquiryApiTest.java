package com.squirrel.tickting.domain.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 예매자 문의 작성·목록·상세. 예매는 실제 좌석 점유 API 로 만든다. (문의는 예매 상태를 따지지 않아서 결제 대기 예매로 충분하다)
 * 테스트의 이미지 저장소 주소는 https://cdn.example.com 이다. (IntegrationTestSupport)
 */
class UserInquiryApiTest extends BookingTestSupport {

	private static final String INQUIRIES = "/api/v1/inquiries";
	private static final String IMAGE = "https://cdn.example.com/public/user_inquiry/user-1/a.jpg";

	@BeforeEach
	@AfterEach
	void cleanInquiries() {
		jdbc.execute("TRUNCATE TABLE inquiry, inquiry_image, inquiry_history RESTART IDENTITY");
	}

	private Map<String, Object> body(String category, Long bookingId, List<String> imageUrls) {
		Map<String, Object> body = new HashMap<>();
		body.put("category", category);
		body.put("bookingId", bookingId);
		body.put("title", " 환불이 아직 들어오지 않았어요 ");
		body.put("content", "11/27 에 취소했는데 아직 환불되지 않았습니다.");
		body.put("images", imageUrls.stream().map(url -> Map.of("url", url)).toList());
		return body;
	}

	private ResultActions create(String bearer, Map<String, Object> body) throws Exception {
		return mockMvc.perform(post(INQUIRIES)
			.header("Authorization", bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private Long bookingOf(User user, Show show, int seatIndex) throws Exception {
		MvcResult booked = book(user.bearer(), show.scheduleId(), List.of(show.seatIds().get(seatIndex)))
			.andExpect(status().isCreated())
			.andReturn();
		return Long.parseLong(read(booked, "$.data.bookingId"));
	}

	@Test
	@DisplayName("내 예매를 골라 사진과 함께 쓰면 답변 대기로 저장되고, 사진 순서와 작성 이력(그때 내용 그대로)이 남는다")
	void createsInquiry() throws Exception {
		Show show = show(1);
		User user = signup("first");
		Long bookingId = bookingOf(user, show, 0);
		String second = "https://cdn.example.com/public/user_inquiry/user-1/b.png";

		MvcResult created = create(user.bearer(), body("CANCEL_REFUND", bookingId, List.of(IMAGE, second)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("WAITING"))
			.andReturn();
		Long inquiryId = Long.parseLong(read(created, "$.data.inquiryId"));

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM inquiry WHERE id = ?", inquiryId);
		assertThat(row.get("author_type")).isEqualTo("USER");
		assertThat(row.get("author_id")).isEqualTo(user.id());
		assertThat(row.get("author_email")).isEqualTo("first@example.com");
		assertThat(row.get("booking_id")).isEqualTo(bookingId);
		assertThat(row.get("business_id")).isNull();
		assertThat(row.get("title")).isEqualTo("환불이 아직 들어오지 않았어요");
		assertThat(jdbc.queryForList("SELECT url FROM inquiry_image WHERE inquiry_id = ? ORDER BY sort_order",
			String.class, inquiryId)).containsExactly(IMAGE, second);

		Map<String, Object> history = jdbc.queryForMap("SELECT * FROM inquiry_history WHERE inquiry_id = ?", inquiryId);
		assertThat(history.get("action")).isEqualTo("CREATED");
		assertThat(history.get("actor_type")).isEqualTo("USER");
		assertThat(history.get("actor_id")).isEqualTo(user.id());
		assertThat(history.get("title")).isEqualTo("환불이 아직 들어오지 않았어요");
		assertThat(history.get("image_urls").toString()).contains(IMAGE, second);

		mockMvc.perform(get(INQUIRIES + "/" + inquiryId).header("Authorization", user.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.category.code").value("CANCEL_REFUND"))
			.andExpect(jsonPath("$.data.imageUrls[1]").value(second))
			.andExpect(jsonPath("$.data.booking.bookingId").value(bookingId))
			.andExpect(jsonPath("$.data.booking.performanceTitle").value("가상 콘서트"))
			.andExpect(jsonPath("$.data.answer").doesNotExist());
	}

	@Test
	@DisplayName("남의 예매를 고르면 없는 예매와 같은 404 이고 문의가 저장되지 않는다")
	void rejectsOthersBooking() throws Exception {
		Show show = show(1);
		Long othersBooking = bookingOf(signup("owner"), show, 0);
		User user = signup("first");

		create(user.bearer(), body("BOOKING", othersBooking, List.of()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BOOKING006"));

		assertThat(count("inquiry")).isZero();
		assertThat(count("inquiry_history")).isZero();
	}

	@Test
	@DisplayName("예매자는 판매자 유형을 고를 수 없어 400 이고, 유형 목록에도 예매자 유형과 공용 유형만 나온다")
	void rejectsSellerCategory() throws Exception {
		User user = signup("first");

		create(user.bearer(), body("SETTLEMENT", null, List.of()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INQUIRY000"));
		assertThat(count("inquiry")).isZero();

		MvcResult categories = mockMvc.perform(get(INQUIRIES + "/categories").header("Authorization", user.bearer()))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(categories.getResponse().getContentAsString())
			.contains("CANCEL_REFUND", "ETC")
			.doesNotContain("SETTLEMENT", "PERFORMANCE", "BUSINESS");
	}

	@Test
	@DisplayName("남의 문의는 목록에 보이지 않고 상세는 404 다")
	void hidesOthersInquiry() throws Exception {
		User owner = signup("owner");
		MvcResult created = create(owner.bearer(), body("ETC", null, List.of())).andExpect(status().isCreated()).andReturn();
		Long inquiryId = Long.parseLong(read(created, "$.data.inquiryId"));
		User other = signup("other");

		mockMvc.perform(get(INQUIRIES).header("Authorization", other.bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(0));
		mockMvc.perform(get(INQUIRIES + "/" + inquiryId).header("Authorization", other.bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INQUIRY001"));
		mockMvc.perform(get(INQUIRIES).header("Authorization", owner.bearer()))
			.andExpect(jsonPath("$.data.content[0].inquiryId").value(inquiryId));
	}

	@Test
	@DisplayName("판매자 토큰으로는 예매자 문의 API 를 쓸 수 없다 (번호가 같은 예매자의 문의도 보이지 않는다)")
	void rejectsSellerToken() throws Exception {
		User user = signup("first");
		create(user.bearer(), body("ETC", null, List.of())).andExpect(status().isCreated());
		String seller = "Bearer " + jwtProvider.createAccessToken(
			new CustomUserDetails(user.id(), AccountType.SELLER, "OWNER", 1L));

		mockMvc.perform(get(INQUIRIES).header("Authorization", seller))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH100"));
	}

	@Test
	@DisplayName("우리 저장소가 아닌 사진 주소는 400 이다 (화면이 그대로 이미지로 쓰는 값이라 외부 주소를 막는다)")
	void rejectsExternalImage() throws Exception {
		User user = signup("first");

		create(user.bearer(), body("ETC", null, List.of("https://evil.example.com/a.jpg")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
		assertThat(count("inquiry")).isZero();
	}
}
