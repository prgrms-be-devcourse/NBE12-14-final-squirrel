package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 판매자의 공연 수정(SLR-03).
 * 시드 공연·공연장을 믿는 다른 테스트가 있어서 테이블을 비우지 않는다. 시드에 없는 사업체 번호와 직접 넣은 공연장을 쓰고,
 * 끝나면 그 데이터만 지운다. 회차·판매 좌석·연기·예매는 만드는 API 가 없거나 이 테스트의 관심이 아니라서 SQL 로 넣는다.
 * 수정 요청 본문은 DB 의 현재 값에서 만들고 바꿀 값만 덮어쓴다. (전체 교체 PUT 이라 안 바꾸는 값도 함께 오기 때문)
 * 시각은 DB 의 NOW() 가 아니라 자바 시각으로 계산해서 넣는다. (서비스가 자바 시각을 기준으로 비교한다)
 */
class SellerPerformanceUpdateApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/seller/performances/{performanceId}";
	private static final long MY_BUSINESS = 931L;
	private static final long OTHER_BUSINESS = 932L;
	private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	@Autowired
	private RedissonClient redissonClient;

	private final List<Long> insertedVenueIds = new ArrayList<>();

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id IN (" + MY_BUSINESS + ", " + OTHER_BUSINESS + ")";
		String schedules = "SELECT id FROM performance_schedule WHERE performance_id IN (" + mine + ")";
		jdbc.update("DELETE FROM schedule_seat WHERE schedule_id IN (" + schedules + ")");
		jdbc.update("DELETE FROM schedule_postponement WHERE schedule_id IN (" + schedules + ")");
		jdbc.update("DELETE FROM booking WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM zone_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM seat_grade WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_image WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance_schedule WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
		jdbc.update("DELETE FROM cancel_policy WHERE business_id IN (?, ?)", MY_BUSINESS, OTHER_BUSINESS);
		for (Long id : insertedVenueIds) {
			jdbc.update("DELETE FROM venue_zone WHERE venue_id = ?", id);
			jdbc.update("DELETE FROM venue WHERE id = ?", id);
		}
		insertedVenueIds.clear();
	}

	// ---- 데이터 준비 ----

	private Cookie token(AccountType type, String role, Long businessId) {
		return sessionCookie(new CustomUserDetails(1L, type, role, businessId));
	}

	/** 수정은 대표 전용이 아니라서 담당자 토큰으로 보낸다. */
	private Cookie staffToken() {
		return token(AccountType.SELLER, "STAFF", MY_BUSINESS);
	}

	private ResultActions update(Long performanceId, Map<String, Object> body) throws Exception {
		return mockMvc.perform(put(URL, performanceId)
			.cookie(staffToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private Long defaultPolicyId() {
		return jdbc.queryForObject("SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
	}

	private Long insertPolicy(long businessId) {
		return jdbc.queryForObject(
			"INSERT INTO cancel_policy (business_id, name, created_at) VALUES (?, '수정 테스트 규정', NOW()) RETURNING id",
			Long.class, businessId);
	}

	private Long arenaId() {
		return jdbc.queryForObject("SELECT id FROM venue WHERE name = '가상 아레나 (샘플)'", Long.class);
	}

	private Long insertVenue(String status, int zoneCount) {
		Long venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('수정 테스트 공연장', '주소', '테스트지역', 100, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, status);
		insertedVenueIds.add(venueId);
		for (int i = 1; i <= zoneCount; i++) {
			jdbc.update("INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, ?, ?)", venueId, i + "구역", i);
		}
		return venueId;
	}

	/** 판매 필드는 비워 둔 공연. 작성 중이나 취소 상태를 만들 때 쓴다. */
	private Long insertPerformance(long businessId, String status, Long policyId, Long venueId, int runningTime) {
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, poster_url, status, created_at, updated_at)
			VALUES (?, ?, 1, ?, '수정 테스트 공연', 'CONCERT', 0, ?, 'https://cdn.example.com/posters/12.jpg', ?,
			        NOW(), NOW())
			RETURNING id
			""", Long.class, businessId, venueId, policyId, runningTime, status);
	}

	private void setSale(Long performanceId, LocalDateTime openAt, LocalDateTime closeAt) {
		jdbc.update("""
			UPDATE performance
			SET sale_open_at = ?, sale_close_at = ?, max_per_person = 4, receive_type = 'ONSITE', booking_fee = 1000
			WHERE id = ?
			""", Timestamp.valueOf(openAt), Timestamp.valueOf(closeAt), performanceId);
	}

	/** 공개된 공연. 판매는 openFromNow 뒤에 열려 closeFromNow 뒤에 닫히고, 회차는 20일 뒤 하나다. */
	private Long insertPublished(Duration openFromNow, Duration closeFromNow) {
		Long performanceId = insertPerformance(MY_BUSINESS, "PUBLISHED", defaultPolicyId(), arenaId(), 120);
		setSale(performanceId, later(openFromNow), later(closeFromNow));
		insertSchedule(performanceId, later(Duration.ofDays(20)), "ON_SALE");
		return performanceId;
	}

	/** 초 아래를 버린 시각. DB 에서 읽은 값과 요청으로 보낸 값이 정확히 같아야 "안 바뀜"으로 보기 때문이다. */
	private static LocalDateTime later(Duration duration) {
		return LocalDateTime.now().plus(duration).truncatedTo(ChronoUnit.SECONDS);
	}

	private Long insertSchedule(Long performanceId, LocalDateTime startAt, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, NOW(), NOW())
			RETURNING id
			""", Long.class, performanceId, Timestamp.valueOf(startAt), status);
	}

	private void insertSeats(Long scheduleId, String status, int count, int firstVenueSeatId) {
		jdbc.update("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, status, version, updated_at)
			SELECT ?, g, 1, ?, 0, NOW() FROM generate_series(?, ?) g
			""", scheduleId, status, firstVenueSeatId, firstVenueSeatId + count - 1);
	}

	private void insertBooking(Long performanceId, String status) {
		jdbc.update("""
			INSERT INTO booking (booking_no, user_id, schedule_id, performance_id, business_id, status, receive_type,
			                     orderer_name, orderer_phone, orderer_email, ticket_amount, booking_fee_amount,
			                     total_amount, cancel_policy_snapshot, expires_at, created_at, updated_at)
			VALUES (?, 1, 1, ?, COALESCE((SELECT business_id FROM performance WHERE id = ?), 0), ?, 'ONSITE',
			        '홍길동', '01012345678', 'buyer@example.com', 1000, 0, 1000,
			        '[]'::jsonb, NOW(), NOW(), NOW())
			""", "T931-" + UUID.randomUUID().toString().substring(0, 8), performanceId, performanceId,
			status);
	}

	/** DB 에 있는 공연의 현재 값 그대로의 요청 본문. 바꿀 값만 덮어써서 쓴다. */
	private Map<String, Object> sameBody(Long performanceId) {
		Map<String, Object> row = jdbc.queryForMap("""
			SELECT venue_id, cancel_policy_id, title, genre, age_limit, running_time, poster_url, sale_open_at,
			       sale_close_at, max_per_person, receive_type, booking_fee
			FROM performance WHERE id = ?
			""", performanceId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("venueId", row.get("venue_id"));
		body.put("cancelPolicyId", row.get("cancel_policy_id"));
		body.put("title", row.get("title"));
		body.put("genre", row.get("genre"));
		body.put("ageLimit", row.get("age_limit"));
		body.put("runningTime", row.get("running_time"));
		body.put("posterUrl", row.get("poster_url"));
		body.put("saleOpenAt", timeOf(row.get("sale_open_at")));
		body.put("saleCloseAt", timeOf(row.get("sale_close_at")));
		body.put("maxPerPerson", row.get("max_per_person"));
		body.put("receiveType", row.get("receive_type"));
		body.put("bookingFee", row.get("booking_fee"));
		return body;
	}

	private static String timeOf(Object timestamp) {
		return timestamp == null ? null : ((Timestamp) timestamp).toLocalDateTime().toString();
	}

	private void assertRejected(ResultActions result, int httpStatus, String code) throws Exception {
		result.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
	}

	private String titleOf(Long performanceId) {
		return jdbc.queryForObject("SELECT title FROM performance WHERE id = ?", String.class, performanceId);
	}

	private Long venueOf(Long performanceId) {
		return jdbc.queryForObject("SELECT venue_id FROM performance WHERE id = ?", Long.class, performanceId);
	}

	private Integer maxPerPersonOf(Long performanceId) {
		return jdbc.queryForObject("SELECT max_per_person FROM performance WHERE id = ?", Integer.class, performanceId);
	}

	// ---- 작성 중 수정 ----

	@Test
	@DisplayName("작성 중 공연은 공연 정보·판매 필드·상세 이미지·취소 규정이 전체 교체되고 담당자도 수정할 수 있으며, 남의 규정은 404 다")
	void draftReplacesEverything() throws Exception {
		Long mine = insertPolicy(MY_BUSINESS);
		Long others = insertPolicy(OTHER_BUSINESS);
		Long id = insertPerformance(MY_BUSINESS, "DRAFT", defaultPolicyId(), arenaId(), 120);
		jdbc.update("INSERT INTO performance_image (performance_id, url, sort_order) VALUES (?, 'https://cdn.example.com/old-1.jpg', 1)", id);
		jdbc.update("INSERT INTO performance_image (performance_id, url, sort_order) VALUES (?, 'https://cdn.example.com/old-2.jpg', 2)", id);

		Map<String, Object> body = sameBody(id);
		body.put("cancelPolicyId", mine);
		body.put("title", "수정한 공연명");
		body.put("runningTime", 100);
		body.put("saleOpenAt", later(Duration.ofDays(2)).toString());
		body.put("saleCloseAt", later(Duration.ofDays(9)).toString());
		body.put("maxPerPerson", 3);
		body.put("receiveType", "ONSITE");
		body.put("bookingFee", 500);
		body.put("images", List.of(Map.of("url", "https://cdn.example.com/new-1.jpg", "sortOrder", 1)));

		update(id, body)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.performanceId").value(id))
			.andExpect(jsonPath("$.data.status").value("DRAFT"));

		Map<String, Object> saved = jdbc.queryForMap(
			"SELECT title, running_time, cancel_policy_id, max_per_person, booking_fee, sale_open_at FROM performance WHERE id = ?", id);
		assertThat(saved).containsEntry("title", "수정한 공연명").containsEntry("running_time", 100)
			.containsEntry("cancel_policy_id", mine).containsEntry("max_per_person", 3).containsEntry("booking_fee", 500);
		assertThat(saved.get("sale_open_at")).isNotNull();
		assertThat(jdbc.queryForList("SELECT url FROM performance_image WHERE performance_id = ?", String.class, id))
			.containsExactly("https://cdn.example.com/new-1.jpg");

		// 남의 사업체 규정은 없는 규정과 같은 404 이고 규정은 바뀌지 않는다.
		Map<String, Object> toOthersPolicy = sameBody(id);
		toOthersPolicy.put("cancelPolicyId", others);
		assertRejected(update(id, toOthersPolicy), 404, "PERFORMANCE100");
		assertThat(sameBody(id).get("cancelPolicyId")).isEqualTo(mine);
	}

	@Test
	@DisplayName("작성 중 공연의 공연장을 바꾸면 구역 설정이 새 공연장 기준 UNSET 으로 초기화되고 등급은 남으며, 판매 좌석(보류석 포함)이 지워지고 취소 회차는 건드리지 않는다")
	void draftVenueChangeResetsZonesAndSeats() throws Exception {
		Long id = insertPerformance(MY_BUSINESS, "DRAFT", defaultPolicyId(), arenaId(), 120);
		Long grade = jdbc.queryForObject(
			"INSERT INTO seat_grade (performance_id, name, price) VALUES (?, 'R', 100000) RETURNING id", Long.class, id);
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, assignment_status)
			SELECT ?, z.id, 'UNSET' FROM venue_zone z WHERE z.venue_id = ?
			""", id, arenaId());
		jdbc.update("UPDATE zone_grade SET assignment_status = 'ASSIGNED', grade_id = ? WHERE id = (SELECT MIN(id) FROM zone_grade WHERE performance_id = ?)",
			grade, id);
		Long active = insertSchedule(id, later(Duration.ofDays(20)), "ON_SALE");
		Long canceled = insertSchedule(id, later(Duration.ofDays(21)), "CANCELED");
		jdbc.update("UPDATE performance_schedule SET seats_generated_at = NOW() WHERE id IN (?, ?)", active, canceled);
		insertSeats(active, "AVAILABLE", 2, 1);
		insertSeats(active, "RESERVED", 1, 100);
		insertSeats(canceled, "AVAILABLE", 1, 1);
		Long newVenue = insertVenue("ACTIVE", 2);

		Map<String, Object> body = sameBody(id);
		body.put("venueId", newVenue);
		update(id, body).andExpect(status().isOk());

		assertThat(venueOf(id)).isEqualTo(newVenue);
		List<Long> newZones = jdbc.queryForList("SELECT id FROM venue_zone WHERE venue_id = ? ORDER BY id", Long.class, newVenue);
		assertThat(jdbc.queryForList("SELECT zone_id FROM zone_grade WHERE performance_id = ? ORDER BY zone_id", Long.class, id))
			.isEqualTo(newZones);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM zone_grade WHERE performance_id = ? AND assignment_status = 'UNSET' AND grade_id IS NULL",
			Integer.class, id)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_grade WHERE performance_id = ?", Integer.class, id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_seat WHERE schedule_id IN (?, ?)", Integer.class, active, canceled))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT seats_generated_at FROM performance_schedule WHERE id = ?", Timestamp.class, active)).isNull();
		assertThat(jdbc.queryForObject("SELECT seats_generated_at FROM performance_schedule WHERE id = ?", Timestamp.class, canceled)).isNotNull();
	}

	@Test
	@DisplayName("없는 공연장은 404, 사용이 중지된 공연장은 400 이고 공연장·구역 설정은 그대로다")
	void draftVenueChangeRejectsBadVenue() throws Exception {
		Long id = insertPerformance(MY_BUSINESS, "DRAFT", defaultPolicyId(), arenaId(), 120);
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, assignment_status)
			SELECT ?, z.id, 'UNSET' FROM venue_zone z WHERE z.venue_id = ?
			""", id, arenaId());
		int zoneCount = jdbc.queryForObject("SELECT COUNT(*) FROM zone_grade WHERE performance_id = ?", Integer.class, id);

		Map<String, Object> missing = sameBody(id);
		missing.put("venueId", Long.MAX_VALUE);
		assertRejected(update(id, missing), 404, "VENUE000");
		Map<String, Object> inactive = sameBody(id);
		inactive.put("venueId", insertVenue("INACTIVE", 1));
		assertRejected(update(id, inactive), 400, "VENUE001");

		assertThat(venueOf(id)).isEqualTo(arenaId());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM zone_grade WHERE performance_id = ?", Integer.class, id)).isEqualTo(zoneCount);
	}

	// ---- 공개 후 수정 ----

	@Test
	@DisplayName("공개된 공연은 공연장·취소 규정을 같은 값으로 보내면 통과하고 다른 값이면 409 PERFORMANCE003·004 다")
	void publishedVenueAndPolicyAreLocked() throws Exception {
		Long id = insertPublished(Duration.ofDays(1), Duration.ofDays(10));

		Map<String, Object> same = sameBody(id);
		same.put("title", "제목만 수정");
		update(id, same).andExpect(status().isOk());
		assertThat(titleOf(id)).isEqualTo("제목만 수정");

		Map<String, Object> otherVenue = sameBody(id);
		otherVenue.put("venueId", insertVenue("ACTIVE", 1));
		assertRejected(update(id, otherVenue), 409, "PERFORMANCE003");
		Map<String, Object> otherPolicy = sameBody(id);
		otherPolicy.put("cancelPolicyId", insertPolicy(MY_BUSINESS));
		assertRejected(update(id, otherPolicy), 409, "PERFORMANCE004");
		assertThat(venueOf(id)).isEqualTo(arenaId());
	}

	@Test
	@DisplayName("공개된 공연의 판매 오픈은 오픈 전에만 3시간 넘게 남는 시각으로 옮길 수 있고, 열린 뒤에는 바꿀 수 없다")
	void publishedSaleOpenRules() throws Exception {
		Long unopened = insertPublished(Duration.ofDays(1), Duration.ofDays(10));
		Map<String, Object> tooSoon = sameBody(unopened);
		tooSoon.put("saleOpenAt", later(Duration.ofHours(3)).toString());
		assertRejected(update(unopened, tooSoon), 400, "PERFORMANCE406");
		Map<String, Object> enough = sameBody(unopened);
		enough.put("saleOpenAt", later(Duration.ofHours(3).plusMinutes(1)).toString());
		update(unopened, enough).andExpect(status().isOk());

		Long opened = insertPublished(Duration.ofDays(-1), Duration.ofDays(10));
		Map<String, Object> moveOpen = sameBody(opened);
		moveOpen.put("saleOpenAt", later(Duration.ofDays(-2)).toString());
		assertRejected(update(opened, moveOpen), 409, "PERFORMANCE005");
		// 오픈을 그대로 두면 종료만 바꿀 수 있다.
		Map<String, Object> shorterClose = sameBody(opened);
		shorterClose.put("saleCloseAt", later(Duration.ofDays(5)).toString());
		update(opened, shorterClose).andExpect(status().isOk());
	}

	@Test
	@DisplayName("공개된 공연의 판매 종료는 지금보다 늦어야 하고, 공개 때와 같이 회차는 오픈 뒤에 시작하고 종료는 마지막 회차 시작을 넘지 못한다")
	void publishedSaleWindowFollowsSchedules() throws Exception {
		Long id = insertPublished(Duration.ofDays(1), Duration.ofDays(10));

		// 종료가 오픈보다 빠르면 요청 형식 검사(001)가 먼저 걸리므로, 오픈이 이미 지난 공연으로 종료만 과거로 보낸다.
		Long opened = insertPublished(Duration.ofDays(-1), Duration.ofDays(10));
		Map<String, Object> closed = sameBody(opened);
		closed.put("saleCloseAt", later(Duration.ofMinutes(-1)).toString());
		assertRejected(update(opened, closed), 400, "PERFORMANCE405");
		// 회차는 20일 뒤다.
		Map<String, Object> afterLast = sameBody(id);
		afterLast.put("saleCloseAt", later(Duration.ofDays(21)).toString());
		assertRejected(update(id, afterLast), 400, "PERFORMANCE408");
		Map<String, Object> scheduleBeforeOpen = sameBody(id);
		scheduleBeforeOpen.put("saleOpenAt", later(Duration.ofDays(21)).toString());
		scheduleBeforeOpen.put("saleCloseAt", later(Duration.ofDays(22)).toString());
		assertRejected(update(id, scheduleBeforeOpen), 400, "PERFORMANCE407");
		Map<String, Object> blank = sameBody(id);
		blank.put("maxPerPerson", null);
		assertRejected(update(id, blank), 400, "PERFORMANCE401");
	}

	@Test
	@DisplayName("1인 최대 매수는 늘릴 수 있고, 좌석을 차지한 예매가 있으면 줄일 수 없지만 전체 취소·만료 예매만 있으면 줄일 수 있다")
	void maxPerPersonReduce() throws Exception {
		Long free = insertPublished(Duration.ofDays(1), Duration.ofDays(10));
		Map<String, Object> increase = sameBody(free);
		increase.put("maxPerPerson", 5);
		update(free, increase).andExpect(status().isOk());
		assertThat(maxPerPersonOf(free)).isEqualTo(5);

		for (String occupying : List.of("PENDING", "PAID", "PARTIAL_CANCELED")) {
			Long booked = insertPublished(Duration.ofDays(1), Duration.ofDays(10));
			insertBooking(booked, occupying);
			Map<String, Object> reduce = sameBody(booked);
			reduce.put("maxPerPerson", 3);
			assertRejected(update(booked, reduce), 409, "PERFORMANCE006");
			assertThat(maxPerPersonOf(booked)).isEqualTo(4);
		}

		Long released = insertPublished(Duration.ofDays(1), Duration.ofDays(10));
		insertBooking(released, "CANCELED");
		insertBooking(released, "EXPIRED");
		Map<String, Object> reduce = sameBody(released);
		reduce.put("maxPerPerson", 3);
		update(released, reduce).andExpect(status().isOk());
		assertThat(maxPerPersonOf(released)).isEqualTo(3);
	}

	// ---- 같은 공연장 회차 시간 겹침 ----

	@Test
	@DisplayName("공연장을 바꿀 때 새 공연장의 다른 회차와 1시간 여유를 두고 겹치면 409 PERFORMANCE007 이고, 끝나는 시각과 다음 시작이 같으면 통과한다")
	void venueChangeOverlap() throws Exception {
		Long venue = insertVenue("ACTIVE", 1);
		LocalDateTime other = LocalDate.now().plusDays(30).atTime(19, 0);
		// 작성 중인 다른 사업체의 공연도 시간을 차지한다. 러닝타임 120분 + 여유 1시간 = 3시간이다.
		Long otherPerformance = insertPerformance(OTHER_BUSINESS, "DRAFT", defaultPolicyId(), venue, 120);
		insertSchedule(otherPerformance, other, "ON_SALE");

		// 겹치는 경우를 먼저 모두 확인한다. 성공한 공연이 새 공연장에 남으면 다음 확인이 그 공연 때문에 막힐 수 있다.
		LocalDateTime justAfter = other.plusHours(3).minusMinutes(1);
		Long overlapAfter = insertDraftWithSchedule(justAfter);
		Map<String, Object> toVenue = sameBody(overlapAfter);
		toVenue.put("venueId", venue);
		update(overlapAfter, toVenue)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PERFORMANCE007"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(MESSAGE_TIME.format(justAfter))));
		assertThat(venueOf(overlapAfter)).isEqualTo(arenaId());

		Long overlapBefore = insertDraftWithSchedule(other.minusHours(3).plusMinutes(1));
		Map<String, Object> toVenue2 = sameBody(overlapBefore);
		toVenue2.put("venueId", venue);
		assertRejected(update(overlapBefore, toVenue2), 409, "PERFORMANCE007");

		Long touchAfter = insertDraftWithSchedule(other.plusHours(3));
		Map<String, Object> toVenue3 = sameBody(touchAfter);
		toVenue3.put("venueId", venue);
		update(touchAfter, toVenue3).andExpect(status().isOk());
		Long touchBefore = insertDraftWithSchedule(other.minusHours(3));
		Map<String, Object> toVenue4 = sameBody(touchBefore);
		toVenue4.put("venueId", venue);
		update(touchBefore, toVenue4).andExpect(status().isOk());
		assertThat(venueOf(touchAfter)).isEqualTo(venue);
	}

	@Test
	@DisplayName("취소된 회차·취소된 공연의 회차·새 일정이 정해지지 않은 연기 회차는 시간을 차지하지 않지만, 새 일정이 정해진 연기 회차는 차지한다")
	void venueChangeIgnoresNonOccupyingSchedules() throws Exception {
		LocalDateTime time = LocalDate.now().plusDays(30).atTime(19, 0);

		Long free = insertVenue("ACTIVE", 1);
		Long canceledSchedule = insertPerformance(OTHER_BUSINESS, "PUBLISHED", defaultPolicyId(), free, 120);
		insertSchedule(canceledSchedule, time, "CANCELED");
		Long canceledPerformance = insertPerformance(OTHER_BUSINESS, "CANCELED", defaultPolicyId(), free, 120);
		insertSchedule(canceledPerformance, time, "ON_SALE");
		Long unsetPostponed = insertPerformance(OTHER_BUSINESS, "PUBLISHED", defaultPolicyId(), free, 120);
		insertPostponement(insertSchedule(unsetPostponed, time, "POSTPONED"), null);
		Long mine = insertDraftWithSchedule(time);
		Map<String, Object> toFree = sameBody(mine);
		toFree.put("venueId", free);
		update(mine, toFree).andExpect(status().isOk());

		Long busy = insertVenue("ACTIVE", 1);
		Long datedPostponed = insertPerformance(OTHER_BUSINESS, "PUBLISHED", defaultPolicyId(), busy, 120);
		insertPostponement(insertSchedule(datedPostponed, time, "POSTPONED"), time.plusDays(10));
		Long blocked = insertDraftWithSchedule(time);
		Map<String, Object> toBusy = sameBody(blocked);
		toBusy.put("venueId", busy);
		assertRejected(update(blocked, toBusy), 409, "PERFORMANCE007");
	}

	@Test
	@DisplayName("러닝타임을 늘려 같은 공연의 다음 회차와 겹치면 409 PERFORMANCE007 이고, 경계까지는 통과하며 줄일 때는 검사하지 않는다")
	void runningTimeIncreaseOverlap() throws Exception {
		Long venue = insertVenue("ACTIVE", 1);
		Long id = insertPerformance(MY_BUSINESS, "DRAFT", defaultPolicyId(), venue, 120);
		LocalDateTime first = LocalDate.now().plusDays(30).atTime(14, 0);
		insertSchedule(id, first, "ON_SALE");
		// 두 번째 회차는 러닝타임 150분 + 여유 1시간이 정확히 끝나는 시각에 시작한다.
		insertSchedule(id, first.plusMinutes(210), "ON_SALE");

		Map<String, Object> tooLong = sameBody(id);
		tooLong.put("runningTime", 151);
		assertRejected(update(id, tooLong), 409, "PERFORMANCE007");

		Map<String, Object> boundary = sameBody(id);
		boundary.put("runningTime", 150);
		update(id, boundary).andExpect(status().isOk());
		Map<String, Object> shorter = sameBody(id);
		shorter.put("runningTime", 60);
		update(id, shorter).andExpect(status().isOk());
	}

	private Long insertDraftWithSchedule(LocalDateTime startAt) {
		Long id = insertPerformance(MY_BUSINESS, "DRAFT", defaultPolicyId(), arenaId(), 120);
		insertSchedule(id, startAt, "ON_SALE");
		return id;
	}

	private void insertPostponement(Long scheduleId, LocalDateTime newStartAt) {
		jdbc.update("""
			INSERT INTO schedule_postponement (schedule_id, old_start_at, new_start_at, reason, free_cancel_until, changed_by, created_at)
			VALUES (?, NOW(), ?, '연기', NOW(), 1, NOW())
			""", scheduleId, newStartAt == null ? null : Timestamp.valueOf(newStartAt));
	}

	// ---- 권한·상태 ----

	@Test
	@DisplayName("취소된 공연은 409 PERFORMANCE002, 다른 사업체의 공연은 없는 공연과 같은 404 이고 바뀌지 않으며, 예매자는 403·토큰이 없으면 401 이다")
	void canceledOthersAndAuth() throws Exception {
		Long canceled = insertPerformance(MY_BUSINESS, "CANCELED", defaultPolicyId(), arenaId(), 120);
		Map<String, Object> canceledBody = sameBody(canceled);
		canceledBody.put("title", "바뀌면 안 됨");
		assertRejected(update(canceled, canceledBody), 409, "PERFORMANCE002");
		assertThat(titleOf(canceled)).isEqualTo("수정 테스트 공연");

		Long others = insertPerformance(OTHER_BUSINESS, "DRAFT", defaultPolicyId(), arenaId(), 120);
		Map<String, Object> othersBody = sameBody(others);
		othersBody.put("title", "남의 공연");
		assertRejected(update(others, othersBody), 404, "PERFORMANCE000");
		assertRejected(update(Long.MAX_VALUE, othersBody), 404, "PERFORMANCE000");
		assertThat(titleOf(others)).isEqualTo("수정 테스트 공연");

		String body = objectMapper.writeValueAsString(othersBody);
		mockMvc.perform(put(URL, canceled).cookie(token(AccountType.USER, null, null))
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		mockMvc.perform(put(URL, canceled).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isUnauthorized());
	}

	// ---- 락·동시성 ----

	@Test
	@DisplayName("다른 작업이 공연 락을 잡고 있으면 1초 기다린 뒤 409 COMMON004 이고 수정되지 않는다")
	void performanceLockHeld() throws Exception {
		Long id = insertPerformance(MY_BUSINESS, "DRAFT", defaultPolicyId(), arenaId(), 120);
		Map<String, Object> body = sameBody(id);
		body.put("title", "락 때문에 안 바뀜");

		holdingLock("performance:" + id, () -> assertRejected(update(id, body), 409, "COMMON004"));

		assertThat(titleOf(id)).isEqualTo("수정 테스트 공연");
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private void holdingLock(String lockName, ThrowingRunnable action) throws Exception {
		RLock lock = redissonClient.getLock("tickting:lock:" + lockName);
		// 락은 잡은 스레드의 것이라, 요청을 보내는 테스트 스레드가 아닌 다른 스레드에서 잡아야 기다리게 된다.
		CompletableFuture.runAsync(() -> lock.lock(10, TimeUnit.SECONDS)).join();
		try {
			action.run();
		} finally {
			lock.forceUnlock();
		}
	}
}
