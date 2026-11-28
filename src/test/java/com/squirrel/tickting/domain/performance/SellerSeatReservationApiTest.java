package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.booking.BookingTestSupport;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** 판매자 보류석 조회(SLR-30·31)·지정(SLR-32)·해제(SLR-33). */
class SellerSeatReservationApiTest extends BookingTestSupport {

	/** BookingTestSupport 가 공연을 넣는 사업체 번호 */
	private static final long MY_BUSINESS = 1L;
	private static final long OTHER_BUSINESS = 986L;
	private static final long SELLER_ID = 55L;

	@Autowired
	private ScheduleSeatService scheduleSeatService;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Test
	@DisplayName("지정은 AVAILABLE 만 RESERVED 로 바꾸고 나머지는 지금 상태와 함께 건너뛰며, 감사 로그 한 줄에 바뀐 좌석만 남는다")
	void reservesOnlyAvailableSeats() throws Exception {
		Show show = beforeSaleShow(5);
		List<Long> seats = show.seatIds();
		changeSeatStatus(seats.get(1), "HELD");
		changeSeatStatus(seats.get(2), "SOLD");
		changeSeatStatus(seats.get(3), "BLOCKED");
		changeSeatStatus(seats.get(4), "RESERVED");

		reserve(show.scheduleId(), Map.of("seatIds", seats))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.changedSeatIds.length()").value(1))
			.andExpect(jsonPath("$.data.changedSeatIds[0]").value(seats.get(0)))
			.andExpect(jsonPath("$.data.skipped[*].status").value(
				contains("HELD", "SOLD", "BLOCKED", "RESERVED")))
			.andExpect(jsonPath("$.data.zones").doesNotExist());

		assertThat(seats.stream().map(this::seatStatus))
			.containsExactly("RESERVED", "HELD", "SOLD", "BLOCKED", "RESERVED");
		assertThat(jdbc.queryForObject("""
			SELECT action || ':' || target_id || ':' || jsonb_array_length(after_value -> 'seats') || ':'
			       || (after_value -> 'seats' -> 0 ->> 'zone') || ':' || (after_value -> 'seats' -> 0 ->> 'number')
			FROM audit_log WHERE target_type = 'SCHEDULE'
			""", String.class)).isEqualTo("SEAT_RESERVE:" + show.scheduleId() + ":1:F2구역:1");
	}

	@Test
	@DisplayName("보류석은 예매할 수 없고, 판매 중에 해제하면 RESERVED 만 AVAILABLE 이 되어 바로 예매할 수 있다")
	void releasesSeatsForSale() throws Exception {
		Show show = show(2);
		Long reserved = show.seatIds().get(0);
		Long sold = show.seatIds().get(1);
		changeSeatStatus(reserved, "RESERVED");
		changeSeatStatus(sold, "SOLD");
		generated(show);
		User user = signup("buyer");
		book(user.session(), show.scheduleId(), List.of(reserved))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BOOKING003"));

		release(show.scheduleId(), Map.of("seatIds", show.seatIds()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.changedSeatIds[0]").value(reserved))
			.andExpect(jsonPath("$.data.skipped[0].status").value("SOLD"));

		assertThat(seatStatus(sold)).isEqualTo("SOLD");
		book(user.session(), show.scheduleId(), List.of(reserved)).andExpect(status().isCreated());
		// 바꿀 것이 없으면 409 이고 로그를 남기지 않는다.
		release(show.scheduleId(), Map.of("seatIds", show.seatIds()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE603"));
		assertThat(auditActions()).containsExactly("SEAT_RELEASE");
	}

	@Test
	@DisplayName("구역 통째 지정은 고른 구역의 AVAILABLE 만 바꾸고, 다른 공연장의 구역이 섞이면 아무것도 바꾸지 않는다")
	void reservesWholeZoneOnly() throws Exception {
		Show show = beforeSaleShow(3);
		changeSeatStatus(show.seatIds().get(2), "SOLD");
		Long gradeId = jdbc.queryForObject("SELECT grade_id FROM schedule_seat WHERE id = ?", Long.class,
			show.seatIds().get(0));
		Long otherZoneId = jdbc.queryForObject(
			"INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, 'F3구역', 2) RETURNING id", Long.class, venueId);
		Long otherZoneVenueSeatId = jdbc.queryForObject("""
			INSERT INTO venue_seat (venue_id, zone_id, row_label, seat_number, sort_order)
			VALUES (?, ?, '1열', '1', 1) RETURNING id
			""", Long.class, venueId, otherZoneId);
		Long otherZoneSeat = saveSeat(show.scheduleId(), otherZoneVenueSeatId, gradeId, "AVAILABLE");
		jdbc.update("UPDATE schedule_seat SET zone_id = ? WHERE id = ?", otherZoneId, otherZoneSeat);
		Long foreignZoneId = jdbc.queryForObject("SELECT id FROM venue_zone WHERE venue_id <> ? LIMIT 1", Long.class,
			venueId);

		reserve(show.scheduleId(), Map.of("zoneIds", List.of(zoneId, foreignZoneId)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE201"));
		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");

		reserve(show.scheduleId(), Map.of("zoneIds", List.of(zoneId, zoneId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.zones.length()").value(1))
			.andExpect(jsonPath("$.data.zones[0].changedSeats").value(2))
			.andExpect(jsonPath("$.data.changedSeatIds").doesNotExist());

		assertThat(show.seatIds().stream().map(this::seatStatus)).containsExactly("RESERVED", "RESERVED", "SOLD");
		assertThat(seatStatus(otherZoneSeat)).isEqualTo("AVAILABLE");
		assertThat(jdbc.queryForObject(
			"SELECT after_value -> 'zones' -> 0 ->> 'changedSeats' FROM audit_log", String.class)).isEqualTo("2");

		release(show.scheduleId(), Map.of("zoneIds", List.of(zoneId, otherZoneId))).andExpect(status().isOk());
		assertThat(show.seatIds().stream().map(this::seatStatus)).containsExactly("AVAILABLE", "AVAILABLE", "SOLD");
	}

	@Test
	@DisplayName("남의 회차는 조회·지정·해제가 404 이고, 다른 회차의 좌석 번호가 섞이면 404 이며 아무것도 바뀌지 않는다")
	void hidesOthersAndRejectsForeignSeat() throws Exception {
		Show show = beforeSaleShow(1);
		Show other = beforeSaleShow(1);
		Long scheduleId = show.scheduleId();
		Map<String, Object> body = Map.of("seatIds", show.seatIds());

		for (String path : List.of("/zones", "/zones/" + zoneId + "/seats")) {
			view(MY_BUSINESS, scheduleId, path).andExpect(status().isOk());
			view(OTHER_BUSINESS, scheduleId, path)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		}
		for (String action : List.of("reserve", "release")) {
			send(OTHER_BUSINESS, scheduleId, action, body)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PERFORMANCE008"));
		}
		reserve(scheduleId, Map.of("seatIds", List.of(show.seatIds().get(0), other.seatIds().get(0))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PERFORMANCE601"));

		assertThat(seatStatus(show.seatIds().get(0))).isEqualTo("AVAILABLE");
		assertThat(seatStatus(other.seatIds().get(0))).isEqualTo("AVAILABLE");
		assertThat(auditActions()).isEmpty();
	}

	@Test
	@DisplayName("지정은 판매 오픈 전까지, 해제는 시작 전까지다. 일정 미정 연기 회차는 원래 일시가 지나도 해제할 수 있다")
	void limitsByScheduleState() throws Exception {
		Show show = beforeSaleShow(2);
		Long scheduleId = show.scheduleId();
		Long reservedSeat = show.seatIds().get(1);
		changeSeatStatus(reservedSeat, "RESERVED");
		Map<String, Object> available = Map.of("seatIds", List.of(show.seatIds().get(0)));
		Map<String, Object> reserved = Map.of("seatIds", List.of(reservedSeat));

		jdbc.update("UPDATE performance SET sale_open_at = ? WHERE id = ?", now.minusMinutes(1), show.performanceId());
		jdbc.update("UPDATE performance_schedule SET status = 'PAUSED' WHERE id = ?", scheduleId);
		assertRejected(reserve(scheduleId, available), 409, "PERFORMANCE604");

		jdbc.update("UPDATE performance_schedule SET status = 'CANCELED' WHERE id = ?", scheduleId);
		assertRejected(release(scheduleId, reserved), 409, "PERFORMANCE501");

		jdbc.update("UPDATE performance_schedule SET status = 'ON_SALE', start_at = ? WHERE id = ?", now.minusDays(1),
			scheduleId);
		assertRejected(release(scheduleId, reserved), 400, "PERFORMANCE502");

		jdbc.update("UPDATE performance_schedule SET seats_generated_at = NULL, start_at = ? WHERE id = ?",
			now.plusDays(10), scheduleId);
		assertRejected(release(scheduleId, reserved), 409, "PERFORMANCE600");
		assertThat(seatStatus(reservedSeat)).isEqualTo("RESERVED");

		jdbc.update("""
			UPDATE performance_schedule SET status = 'POSTPONED', start_at = ?, seats_generated_at = ? WHERE id = ?
			""", now.minusDays(1), now, scheduleId);
		release(scheduleId, reserved).andExpect(status().isOk());
		assertThat(seatStatus(reservedSeat)).isEqualTo("AVAILABLE");
	}

	@Test
	@DisplayName("같은 좌석을 동시에 지정하면 하나만 성공하고 감사 로그는 한 줄이다")
	void concurrentReserveSavesOnce() throws Exception {
		Show show = beforeSaleShow(3);

		List<Integer> statuses = runConcurrently(4, i -> () ->
			reserve(show.scheduleId(), Map.of("seatIds", show.seatIds())).andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnlyOnce(200).containsOnly(200, 409);
		assertThat(auditActions()).containsExactly("SEAT_RESERVE");
	}

	/** 지정은 판매 오픈 전에만 되고 점유는 오픈 뒤에만 돼서 API 로는 겹치게 만들 수 없다. 두 상태 조건부 UPDATE 를 직접 겹친다. */
	@Test
	@DisplayName("같은 좌석에 예매자 점유와 판매자 지정이 겹치면 한쪽만 성공한다")
	void holdAndReserveNeverBothWin() throws Exception {
		Show show = beforeSaleShow(1);
		Long seatId = show.seatIds().get(0);

		for (int round = 0; round < 10; round++) {
			changeSeatStatus(seatId, "AVAILABLE");

			List<String> winners = runConcurrently(2, i -> () -> {
				try {
					transactionTemplate.executeWithoutResult(status -> {
						if (i == 0) {
							scheduleSeatService.holdSeats(show.scheduleId(), Set.of(seatId), 1L, now, now.plusMinutes(7));
						} else {
							scheduleSeatService.changeReservation(show.scheduleId(), Set.of(seatId), true, now);
						}
					});
					return i == 0 ? "HELD" : "RESERVED";
				} catch (BusinessException e) {
					return "LOST";
				}
			});

			assertThat(winners).containsOnlyOnce("LOST").contains(seatStatus(seatId));
		}
	}

	// ---------------------------------------------------------------- 데이터·호출 도우미

	/** 판매 오픈이 내일인 공개 공연. 좌석은 생성된 것으로 표시한다. */
	private Show beforeSaleShow(int seatCount) {
		Show show = show(seatCount, "PUBLISHED", "ON_SALE", now.plusDays(10), now.plusDays(1), now.plusDays(9), "BOTH",
			2000);
		generated(show);
		return show;
	}

	private void generated(Show show) {
		jdbc.update("UPDATE performance_schedule SET seats_generated_at = ? WHERE id = ?", now, show.scheduleId());
	}

	private void changeSeatStatus(Long seatId, String status) {
		jdbc.update("UPDATE schedule_seat SET status = ? WHERE id = ?", status, seatId);
	}

	private List<String> auditActions() {
		return jdbc.queryForList("SELECT action FROM audit_log WHERE target_type = 'SCHEDULE' ORDER BY id",
			String.class);
	}

	private void assertRejected(ResultActions result, int httpStatus, String code) throws Exception {
		result.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
	}

	private ResultActions reserve(Long scheduleId, Map<String, Object> body) throws Exception {
		return send(MY_BUSINESS, scheduleId, "reserve", body);
	}

	private ResultActions release(Long scheduleId, Map<String, Object> body) throws Exception {
		return send(MY_BUSINESS, scheduleId, "release", body);
	}

	private ResultActions send(long businessId, Long scheduleId, String action, Map<String, Object> body)
		throws Exception {
		return mockMvc.perform(post("/api/v1/seller/schedules/" + scheduleId + "/seats/" + action)
			.cookie(seller(businessId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private ResultActions view(long businessId, Long scheduleId, String path) throws Exception {
		return mockMvc.perform(get("/api/v1/seller/schedules/" + scheduleId + path)
			.cookie(seller(businessId)));
	}

	/** 담당자 세션 */
	private Cookie seller(long businessId) {
		return sessionCookie(new CustomUserDetails(SELLER_ID, AccountType.SELLER, "STAFF", businessId));
	}
}
