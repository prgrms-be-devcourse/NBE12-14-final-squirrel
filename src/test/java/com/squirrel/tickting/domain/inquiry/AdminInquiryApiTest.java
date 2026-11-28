package com.squirrel.tickting.domain.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.http.Cookie;
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

/** 관리자 문의 목록·상세·답변. 문의는 예매자 API 로 만든다. */
class AdminInquiryApiTest extends BookingTestSupport {

	private static final String INQUIRIES = "/api/v1/inquiries";
	private static final String ADMIN_INQUIRIES = "/api/v1/admin/inquiries";
	private static final Long ADMIN_ID = 1L;

	@BeforeEach
	@AfterEach
	void cleanInquiries() {
		jdbc.execute("TRUNCATE TABLE inquiry, inquiry_history RESTART IDENTITY");
	}

	private Cookie adminSession(Long adminId) {
		return sessionCookie(new CustomUserDetails(adminId, AccountType.ADMIN, null, null));
	}

	private Long createInquiry(User user, Long bookingId, String title) throws Exception {
		Map<String, Object> body = new HashMap<>();
		body.put("category", "CANCEL_REFUND");
		body.put("bookingId", bookingId);
		body.put("title", title);
		body.put("content", "취소했는데 아직 환불되지 않았습니다.");
		body.put("images", List.of());
		MvcResult created = mockMvc.perform(post(INQUIRIES)
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isCreated())
			.andReturn();
		return Long.parseLong(read(created, "$.data.inquiryId"));
	}

	private ResultActions answer(Cookie admin, Long inquiryId, String answer) throws Exception {
		return mockMvc.perform(post(ADMIN_INQUIRIES + "/" + inquiryId + "/answer")
			.cookie(admin)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("answer", answer))));
	}

	private List<String> historyActions(Long inquiryId) {
		return jdbc.queryForList("SELECT action FROM inquiry_history WHERE inquiry_id = ? ORDER BY id", String.class,
			inquiryId);
	}

	@Test
	@DisplayName("답변하면 답변 완료가 되고 답변 이력(관리자, 그때 내용)이 남으며, 다시 답변하면 409 이고 처음 답변이 그대로다")
	void answersOnce() throws Exception {
		Show show = show(1);
		User user = signup("first");
		MvcResult booked = book(user.session(), show.scheduleId(), List.of(show.seatIds().get(0)))
			.andExpect(status().isCreated())
			.andReturn();
		Long bookingId = Long.parseLong(read(booked, "$.data.bookingId"));
		Long inquiryId = createInquiry(user, bookingId, "환불 문의");

		answer(adminSession(ADMIN_ID), inquiryId, " 3~5영업일 안에 환불됩니다. ")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ANSWERED"))
			.andExpect(jsonPath("$.data.answer").value("3~5영업일 안에 환불됩니다."))
			.andExpect(jsonPath("$.data.answeredBy").value(ADMIN_ID))
			.andExpect(jsonPath("$.data.booking.performanceTitle").value("가상 콘서트"))
			.andExpect(jsonPath("$.data.histories[1].action").value("ANSWERED"))
			.andExpect(jsonPath("$.data.histories[1].actorType").value("ADMIN"))
			.andExpect(jsonPath("$.data.histories[1].title").value("환불 문의"))
			.andExpect(jsonPath("$.data.histories[1].answer").value("3~5영업일 안에 환불됩니다."));

		answer(adminSession(2L), inquiryId, "다른 답변")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INQUIRY003"));

		Map<String, Object> row = jdbc.queryForMap("SELECT answer, answered_by FROM inquiry WHERE id = ?", inquiryId);
		assertThat(row.get("answer")).isEqualTo("3~5영업일 안에 환불됩니다.");
		assertThat(row.get("answered_by")).isEqualTo(ADMIN_ID);
		assertThat(historyActions(inquiryId)).containsExactly("CREATED", "ANSWERED");
	}

	@Test
	@DisplayName("삭제된 문의는 기본 목록에 없고 deleted=true 일 때만 나오며, 상세는 보이지만 답변하면 404 다")
	void separatesDeletedInquiries() throws Exception {
		User user = signup("first");
		Long kept = createInquiry(user, null, "남긴 문의");
		Long removed = createInquiry(user, null, "지운 문의");
		mockMvc.perform(delete(INQUIRIES + "/" + removed).cookie(user.session())).andExpect(status().isOk());
		Cookie admin = adminSession(ADMIN_ID);

		mockMvc.perform(get(ADMIN_INQUIRIES).cookie(admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].inquiryId").value(kept));
		mockMvc.perform(get(ADMIN_INQUIRIES).param("deleted", "true").cookie(admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].inquiryId").value(removed))
			.andExpect(jsonPath("$.data.content[0].deletedAt").exists());

		mockMvc.perform(get(ADMIN_INQUIRIES + "/" + removed).cookie(admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.title").value("지운 문의"))
			.andExpect(jsonPath("$.data.histories[1].action").value("DELETED"));
		answer(admin, removed, "답변")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INQUIRY001"));
		assertThat(historyActions(removed)).containsExactly("CREATED", "DELETED");
	}

	@Test
	@DisplayName("여러 관리자가 동시에 답변해도 하나만 성공하고 답변 이력도 하나다")
	void answersOnceConcurrently() throws Exception {
		User user = signup("first");
		Long inquiryId = createInquiry(user, null, "환불 문의");

		List<Integer> statuses = runConcurrently(5, i -> () ->
			answer(adminSession((long) i + 1), inquiryId, "답변 " + i).andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnlyOnce(200);
		assertThat(statuses).allMatch(code -> code == 200 || code == 409);
		assertThat(historyActions(inquiryId)).containsExactly("CREATED", "ANSWERED");
	}

	@Test
	@DisplayName("작성자 수정과 관리자 답변이 동시에 들어오면 먼저 끝난 쪽만 성공하고, 답변 이력의 내용은 저장된 문의 내용과 같다")
	void answerAndUpdateDoNotOverlap() throws Exception {
		User user = signup("first");
		Long inquiryId = createInquiry(user, null, "처음 제목");
		Map<String, Object> changed = new HashMap<>();
		changed.put("category", "CANCEL_REFUND");
		changed.put("title", "바꾼 제목");
		changed.put("content", "바꾼 내용");
		changed.put("images", List.of());
		String changedBody = objectMapper.writeValueAsString(changed);

		List<Integer> statuses = runConcurrently(2, i -> () -> (i == 0
			? answer(adminSession(ADMIN_ID), inquiryId, "답변")
			: mockMvc.perform(put(INQUIRIES + "/" + inquiryId)
				.cookie(user.session())
				.contentType(MediaType.APPLICATION_JSON)
				.content(changedBody)))
			.andReturn().getResponse().getStatus());

		assertThat(statuses).allMatch(code -> code == 200 || code == 409);
		Map<String, Object> answered = jdbc.queryForMap(
			"SELECT h.title FROM inquiry_history h WHERE h.inquiry_id = ? AND h.action = 'ANSWERED'", inquiryId);
		String title = jdbc.queryForObject("SELECT title FROM inquiry WHERE id = ?", String.class, inquiryId);
		// 수정이 먼저면 답변은 바꾼 내용에, 답변이 먼저면 수정은 409 라 처음 내용에 답변이 붙는다
		assertThat(answered.get("title")).isEqualTo(title);
	}
}
