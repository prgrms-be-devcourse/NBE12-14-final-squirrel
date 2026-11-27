package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 회차 판매 일시 중지·재개(SLR-26·27)와 그에 따라 달라지는 회차 추가(SLR-12), 공연 목록·상세의 판매 상태.
 * 결제 차단은 PaymentConfirmApiTest, 대기열은 QueueApiTest, 감사 로그는 SellerAuditLogApiTest 가 본다.
 */
class SellerScheduleSaleApiTest extends BookingTestSupport {

	/** BookingTestSupport 가 공연을 넣는 사업체 번호 */
	private static final long MY_BUSINESS = 1L;
	private static final long OTHER_BUSINESS = 986L;

	@Test
	@DisplayName("모두 중지는 판매 중이고 시작 전인 회차만 바꾸고 나머지는 이유를 알려 주며, 모두 재개는 따로 중지한 회차까지 열고 사유를 비운다")
	void pausesAndResumes() throws Exception {
		Show show = show(1);
		Long first = show.scheduleId();
		Long later = insertSchedule(show, now.plusDays(20), "ON_SALE", null);
		Long separately = insertSchedule(show, now.plusDays(12), "PAUSED", "장비 점검");
		Long canceled = insertSchedule(show, now.plusDays(13), "CANCELED", null);
		Long postponed = insertSchedule(show, now.plusDays(14), "POSTPONED", null);
		Long started = insertSchedule(show, now.minusHours(1), "ON_SALE", null);

		MvcResult paused = pause(show, Map.of("all", true, "reason", "  좌석 점검  "))
			.andExpect(status().isOk())
			.andReturn();

		assertThat(read(paused, "$.data.changedScheduleIds")).isEqualTo("[" + first + "," + later + "]");
		assertThat(read(paused, "$.data.skipped[*].scheduleId"))
			.isEqualTo("[" + started + "," + separately + "," + canceled + "," + postponed + "]");
		assertThat(read(paused, "$.data.skipped[*].reason"))
			.isEqualTo("[\"STARTED\",\"ALREADY_PAUSED\",\"CANCELED\",\"POSTPONED\"]");
		assertThat(schedule(first)).isEqualTo("PAUSED:좌석 점검");
		assertThat(schedule(separately)).isEqualTo("PAUSED:장비 점검");
		assertThat(schedule(canceled)).isEqualTo("CANCELED:null");
		assertThat(schedule(started)).isEqualTo("ON_SALE:null");

		pause(show, Map.of("scheduleIds", List.of(first, separately), "reason", "다시"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE016"));

		resume(show, Map.of("scheduleIds", List.of(first, first))).andExpect(status().isOk());
		assertThat(schedule(first)).isEqualTo("ON_SALE:null");
		assertThat(schedule(later)).isEqualTo("PAUSED:좌석 점검");

		resume(show, Map.of("all", true)).andExpect(status().isOk());
		assertThat(schedule(later)).isEqualTo("ON_SALE:null");
		assertThat(schedule(separately)).isEqualTo("ON_SALE:null");
		resume(show, Map.of("all", true))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE017"));
	}

	@Test
	@DisplayName("남의 공연과 다른 공연의 회차 번호는 404, 작성 중·취소된 공연은 409, 대상을 하나로 정하지 않으면 400이고 아무것도 바뀌지 않는다")
	void rejectsWithoutChanging() throws Exception {
		Show show = show(1);
		Show other = show(1);
		Map<String, Object> all = Map.of("all", true, "reason", "점검");

		send(OTHER_BUSINESS, show, "pause", all).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		send(OTHER_BUSINESS, show, "resume", Map.of("all", true)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		pause(show, Map.of("scheduleIds", List.of(show.scheduleId(), other.scheduleId()), "reason", "점검"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		pause(show, Map.of("all", true, "scheduleIds", List.of(show.scheduleId()), "reason", "점검"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
		pause(show, Map.of("all", false, "reason", "점검"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));

		jdbc.update("UPDATE performance SET status = 'DRAFT' WHERE id = ?", show.performanceId());
		pause(show, all).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERFORMANCE015"));
		jdbc.update("UPDATE performance SET status = 'CANCELED' WHERE id = ?", show.performanceId());
		pause(show, all).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERFORMANCE002"));

		assertThat(schedule(show.scheduleId())).isEqualTo("ON_SALE:null");
		assertThat(schedule(other.scheduleId())).isEqualTo("ON_SALE:null");
		assertThat(count("audit_log")).isZero();
	}

	@Test
	@DisplayName("같은 회차를 동시에 중지하면 하나만 성공하고 감사 로그는 한 줄이다")
	void concurrentPauseLogsOnce() throws Exception {
		Show show = show(1);

		List<Integer> statuses = runConcurrently(4, i -> () ->
			pause(show, Map.of("all", true, "reason", "점검")).andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnlyOnce(200).containsOnly(200, 409);
		assertThat(count("audit_log")).isEqualTo(1);
	}

	@Test
	@DisplayName("시작 전 회차가 모두 중지면 새 회차도 중지 상태로, 하나라도 판매 중이면 판매 중으로 추가한다")
	void addsScheduleWithPausedState() throws Exception {
		Show show = show(1);
		// 판매 오픈이 가까우면 회차를 추가할 때 좌석까지 만들어 구역 설정이 필요하므로 오픈을 멀리 둔다.
		jdbc.update("UPDATE performance SET sale_open_at = ? WHERE id = ?", now.plusDays(2), show.performanceId());
		// 시작한 판매 중 회차와 취소 회차는 판단에 넣지 않는다.
		insertSchedule(show, now.minusHours(1), "ON_SALE", null);
		insertSchedule(show, now.plusDays(13), "CANCELED", null);
		pause(show, Map.of("all", true, "reason", "점검")).andExpect(status().isOk());

		addSchedule(show, now.plusDays(20))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("PAUSED"));
		Long added = jdbc.queryForObject("SELECT max(id) FROM performance_schedule", Long.class);
		assertThat(schedule(added)).isEqualTo("PAUSED:다른 회차가 모두 중지되어 중지 상태로 추가됨");

		resume(show, Map.of("scheduleIds", List.of(added))).andExpect(status().isOk());
		addSchedule(show, now.plusDays(25))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("ON_SALE"));
	}

	@Test
	@DisplayName("공연 목록·상세는 시작 전 회차가 모두 중지면 PAUSED, 판매 중 회차가 매진이면 중지 회차의 좌석이 남아도 SOLD_OUT 이다")
	void showsPausedSaleStatus() throws Exception {
		Show show = show(1);
		// 공연 상세는 사업체 정보를 함께 준다. 테이블을 비우고 시작하므로 번호가 MY_BUSINESS 인 행이 생긴다.
		jdbc.update("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone, contact_email,
			                             terms_agreed_at, status, created_at, updated_at)
			VALUES ('(주)가상기획', '김대표', '1234567890', '01012345678', 'contact@company.com', ?, 'APPROVED', ?, ?)
			""", now, now, now);
		jdbc.update("UPDATE performance SET title = '중지 표시 공연' WHERE id = ?", show.performanceId());
		jdbc.update("UPDATE performance_schedule SET status = 'PAUSED' WHERE id = ?", show.scheduleId());

		assertSaleStatus(show, "PAUSED");
		// 오픈 전이어도 재개하지 않으면 열리지 않으므로 일시 중지다.
		jdbc.update("UPDATE performance SET sale_open_at = ? WHERE id = ?", now.plusDays(1), show.performanceId());
		assertSaleStatus(show, "PAUSED");
		jdbc.update("UPDATE performance SET sale_open_at = ? WHERE id = ?", now.minusDays(1), show.performanceId());

		// 좌석이 없는 판매 중 회차를 더하면, 남은 좌석은 중지 회차에만 있다.
		insertSchedule(show, now.plusDays(20), "ON_SALE", null);
		mockMvc.perform(get("/api/v1/performances").param("keyword", "중지 표시 공연"))
			.andExpect(jsonPath("$.data.content[0].saleStatus").value("SOLD_OUT"));
		mockMvc.perform(get("/api/v1/performances/" + show.performanceId()))
			.andExpect(jsonPath("$.data.schedules[0].saleStatus").value("PAUSED"))
			.andExpect(jsonPath("$.data.schedules[1].saleStatus").value("SOLD_OUT"));

		jdbc.update("UPDATE performance SET sale_close_at = ? WHERE id = ?", now.minusMinutes(1), show.performanceId());
		assertSaleStatus(show, "ENDED");
	}

	private void assertSaleStatus(Show show, String saleStatus) throws Exception {
		mockMvc.perform(get("/api/v1/performances").param("keyword", "중지 표시 공연"))
			.andExpect(jsonPath("$.data.content[0].saleStatus").value(saleStatus));
		mockMvc.perform(get("/api/v1/performances/" + show.performanceId()))
			.andExpect(jsonPath("$.data.schedules[0].saleStatus").value(saleStatus));
	}

	private Long insertSchedule(Show show, LocalDateTime startAt, String scheduleStatus, String pauseReason) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, pause_reason, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?) RETURNING id
			""", Long.class, show.performanceId(), Timestamp.valueOf(startAt), scheduleStatus, pauseReason, now, now);
	}

	/** "상태:중지 사유" */
	private String schedule(Long scheduleId) {
		return jdbc.queryForObject(
			"SELECT status || ':' || COALESCE(pause_reason, 'null') FROM performance_schedule WHERE id = ?",
			String.class, scheduleId);
	}

	private ResultActions pause(Show show, Map<String, Object> body) throws Exception {
		return send(MY_BUSINESS, show, "pause", body);
	}

	private ResultActions resume(Show show, Map<String, Object> body) throws Exception {
		return send(MY_BUSINESS, show, "resume", body);
	}

	private ResultActions send(long businessId, Show show, String action, Map<String, Object> body) throws Exception {
		return sendAsSeller(businessId,
			"/api/v1/seller/performances/" + show.performanceId() + "/schedules/" + action, body);
	}

	private ResultActions addSchedule(Show show, LocalDateTime startAt) throws Exception {
		return sendAsSeller(MY_BUSINESS, "/api/v1/seller/performances/" + show.performanceId() + "/schedules",
			Map.of("startAt", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").format(startAt)));
	}

	/** 담당자 토큰으로 보낸다. */
	private ResultActions sendAsSeller(long businessId, String url, Object body) throws Exception {
		return mockMvc.perform(post(url)
			.cookie(sessionCookie(new CustomUserDetails(55L, AccountType.SELLER, "STAFF", businessId)))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}
}
