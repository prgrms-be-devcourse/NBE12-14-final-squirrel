package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.audit.infra.repository.AuditLogRepository;
import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * 판매자 변경 행동의 감사 로그(SLR-03·04·09·10·11·12·13). 무엇이 남는지, 바뀐 것이 없으면 남지 않는지,
 * 로그를 남기지 못하면 변경도 취소되는지 본다.
 */
@Import(SellerAuditLogApiTest.SwitchableAuditConfig.class)
class SellerAuditLogApiTest extends IntegrationTestSupport {

	private static final long MY_BUSINESS = 971L;
	private static final long ACTOR = 55L;
	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
	private static final AtomicBoolean AUDIT_FAILS = new AtomicBoolean();

	/** 켜면 기록이 실패하는 감사 로그 서비스. (Mockito 없이 실패 상황을 만든다) */
	@TestConfiguration(proxyBeanMethods = false)
	static class SwitchableAuditConfig {

		@Bean
		@Primary
		AuditLogService switchableAuditLogService(AuditLogRepository repository, ObjectMapper objectMapper) {
			return new AuditLogService(repository, objectMapper) {
				@Override
				public void saveHistory(AuditLogCommand command) {
					if (AUDIT_FAILS.get()) {
						throw new IllegalStateException("로그 저장 실패");
					}
					super.saveHistory(command);
				}
			};
		}
	}

	private long venueId;
	private long otherVenueId;
	private long zoneA;
	private long zoneB;

	@BeforeEach
	void insertVenues() {
		AUDIT_FAILS.set(false);
		venueId = insertVenue("감사 로그 테스트 공연장");
		zoneA = insertZone(venueId, "A구역", 1);
		zoneB = insertZone(venueId, "B구역", 2);
		otherVenueId = insertVenue("감사 로그 새 공연장");
		insertZone(otherVenueId, "C구역", 1);
	}

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id = " + MY_BUSINESS;
		for (String table : List.of("zone_grade", "seat_grade", "performance_image", "performance_schedule")) {
			jdbc.update("DELETE FROM " + table + " WHERE performance_id IN (" + mine + ")");
		}
		jdbc.update("DELETE FROM performance WHERE business_id = ?", MY_BUSINESS);
		jdbc.update("DELETE FROM venue_zone WHERE venue_id IN (?, ?)", venueId, otherVenueId);
		jdbc.update("DELETE FROM venue WHERE id IN (?, ?)", venueId, otherVenueId);
	}

	@Test
	@DisplayName("변경마다 행위자·대상·전후 값이 한 줄씩 남고, 바뀐 것이 없거나 거절된 요청은 남지 않는다")
	void recordsOnlyRealChanges() throws Exception {
		Map<String, Object> body = performanceBody(venueId);
		long performanceId = register(body);
		assertThat(count("audit_log")).isZero();

		String grades = "/api/v1/seller/performances/" + performanceId + "/grades";
		send(HttpMethod.POST, grades, Map.of("name", "R", "price", 1000)).andExpect(status().isCreated());
		long gradeId = id("seat_grade", performanceId);
		assertLatest(1, "SEAT_GRADE", gradeId, "CREATE");
		assertThat(latest("before_value::text")).isNull();
		assertThat(latest("after_value ->> 'price'")).isEqualTo("1000");

		send(HttpMethod.PUT, grades + "/" + gradeId, Map.of("name", "R", "price", 1000)).andExpect(status().isOk());
		assertThat(count("audit_log")).isEqualTo(1);
		send(HttpMethod.PUT, grades + "/" + gradeId, Map.of("name", "R", "price", 2000)).andExpect(status().isOk());
		assertLatest(2, "SEAT_GRADE", gradeId, "UPDATE");
		assertThat(latest("before_value ->> 'price'")).isEqualTo("1000");
		assertThat(latest("after_value ->> 'price'")).isEqualTo("2000");
		assertThat(latest("after_value ->> 'performanceId'")).isEqualTo(String.valueOf(performanceId));

		String zones = "/api/v1/seller/performances/" + performanceId + "/zone-grades";
		Map<String, Object> assign = Map.of("assignments",
			List.of(zone(zoneA, "ASSIGNED", gradeId), zone(zoneB, "INTENTIONALLY_UNSOLD", null)));
		send(HttpMethod.PATCH, zones, assign).andExpect(status().isOk());
		assertLatest(3, "PERFORMANCE", performanceId, "ZONE_ASSIGN");
		send(HttpMethod.PATCH, zones, assign).andExpect(status().isOk());
		assertThat(count("audit_log")).isEqualTo(3);
		send(HttpMethod.PATCH, zones, Map.of("assignments",
			List.of(zone(zoneA, "ASSIGNED", gradeId), zone(zoneB, "ASSIGNED", gradeId)))).andExpect(status().isOk());
		assertThat(latest("jsonb_array_length(after_value -> 'zones')::text")).isEqualTo("1");
		assertThat(latest("before_value #>> '{zones,0,assignmentStatus}'")).isEqualTo("INTENTIONALLY_UNSOLD");
		assertThat(latest("after_value #>> '{zones,0,zoneName}'")).isEqualTo("B구역");
		assertThat(latest("after_value #>> '{zones,0,gradeName}'")).isEqualTo("R");

		LocalDateTime startAt = daysLater(30);
		send(HttpMethod.POST, "/api/v1/seller/performances/" + performanceId + "/schedules",
			Map.of("startAt", FORMAT.format(startAt))).andExpect(status().isCreated());
		long scheduleId = id("performance_schedule", performanceId);
		assertLatest(5, "SCHEDULE", scheduleId, "CREATE");
		assertThat(latest("after_value ->> 'startAt'")).isEqualTo(FORMAT.format(startAt));
		assertThat(latest("after_value ->> 'seatsGenerated'")).isEqualTo("false");

		String schedule = "/api/v1/seller/schedules/" + scheduleId;
		send(HttpMethod.PUT, schedule, Map.of("startAt", FORMAT.format(startAt))).andExpect(status().isOk());
		assertThat(count("audit_log")).isEqualTo(5);
		send(HttpMethod.PUT, schedule, Map.of("startAt", FORMAT.format(daysLater(31)))).andExpect(status().isOk());
		assertLatest(6, "SCHEDULE", scheduleId, "UPDATE");
		assertThat(latest("before_value ->> 'startAt'")).isEqualTo(FORMAT.format(startAt));
		assertThat(latest("after_value ->> 'startAt'")).isEqualTo(FORMAT.format(daysLater(31)));

		String performance = "/api/v1/seller/performances/" + performanceId;
		send(HttpMethod.PUT, performance, body).andExpect(status().isOk());
		assertThat(count("audit_log")).isEqualTo(6);
		putSaleFields(body);
		send(HttpMethod.PUT, performance, body).andExpect(status().isOk());
		assertLatest(7, "PERFORMANCE", performanceId, "UPDATE");
		// 비어 있던 값은 변경 전에 null 로 남는다.
		assertThat(latest("(before_value -> 'bookingFee')::text")).isEqualTo("null");
		assertThat(latest("after_value ->> 'bookingFee'")).isEqualTo("1000");
		assertThat(latest("jsonb_exists(after_value, 'title')::text")).isEqualTo("false");
		body.put("images", List.of(Map.of("url", "https://cdn.example.com/details/new.jpg", "sortOrder", 1)));
		send(HttpMethod.PUT, performance, body).andExpect(status().isOk());
		assertThat(latest("(SELECT string_agg(k, ',') FROM jsonb_object_keys(after_value) k)")).isEqualTo("images");

		send(HttpMethod.POST, performance + "/publish", null).andExpect(status().isOk());
		assertLatest(9, "PERFORMANCE", performanceId, "PUBLISH");
		assertThat(latest("before_value ->> 'status'")).isEqualTo("DRAFT");
		assertThat(latest("after_value ->> 'status'")).isEqualTo("PUBLISHED");
		send(HttpMethod.POST, performance + "/publish", null).andExpect(status().isConflict());
		assertThat(count("audit_log")).isEqualTo(9);
	}

	@Test
	@DisplayName("작성 중 공연의 공연장을 바꾸면 구역 설정과 판매 좌석이 초기화됐다는 것까지 남는다")
	void recordsVenueChange() throws Exception {
		Map<String, Object> body = performanceBody(venueId);
		long performanceId = register(body);
		body.put("venueId", otherVenueId);

		send(HttpMethod.PUT, "/api/v1/seller/performances/" + performanceId, body).andExpect(status().isOk());

		assertThat(latest("before_value ->> 'venueId'")).isEqualTo(String.valueOf(venueId));
		assertThat(latest("after_value ->> 'venueId'")).isEqualTo(String.valueOf(otherVenueId));
		assertThat(latest("after_value ->> 'zonesAndSeatsReset'")).isEqualTo("true");
	}

	@Test
	@DisplayName("감사 로그를 남기지 못하면 일곱 가지 변경이 모두 취소된다")
	void auditFailureRollsBackEveryChange() throws Exception {
		Map<String, Object> body = performanceBody(venueId);
		putSaleFields(body);
		long performanceId = register(body);
		String performance = "/api/v1/seller/performances/" + performanceId;
		send(HttpMethod.POST, performance + "/grades", Map.of("name", "R", "price", 1000))
			.andExpect(status().isCreated());
		long gradeId = id("seat_grade", performanceId);
		Map<String, Object> assign = Map.of("assignments",
			List.of(zone(zoneA, "ASSIGNED", gradeId), zone(zoneB, "INTENTIONALLY_UNSOLD", null)));
		send(HttpMethod.PATCH, performance + "/zone-grades", assign).andExpect(status().isOk());
		send(HttpMethod.POST, performance + "/schedules", Map.of("startAt", FORMAT.format(daysLater(30))))
			.andExpect(status().isCreated());
		long scheduleId = id("performance_schedule", performanceId);
		List<List<Map<String, Object>>> before = state(performanceId);
		AUDIT_FAILS.set(true);

		expectFailure(HttpMethod.POST, performance + "/grades", Map.of("name", "S", "price", 500));
		expectFailure(HttpMethod.PUT, performance + "/grades/" + gradeId, Map.of("name", "R", "price", 2000));
		expectFailure(HttpMethod.PATCH, performance + "/zone-grades",
			Map.of("assignments", List.of(zone(zoneB, "ASSIGNED", gradeId))));
		expectFailure(HttpMethod.POST, performance + "/schedules", Map.of("startAt", FORMAT.format(daysLater(40))));
		expectFailure(HttpMethod.PUT, "/api/v1/seller/schedules/" + scheduleId,
			Map.of("startAt", FORMAT.format(daysLater(31))));
		body.put("title", "바뀐 제목");
		expectFailure(HttpMethod.PUT, performance, body);
		expectFailure(HttpMethod.POST, performance + "/publish", null);

		assertThat(state(performanceId)).isEqualTo(before);
	}

	private void expectFailure(HttpMethod method, String url, Object body) throws Exception {
		send(method, url, body).andExpect(status().isInternalServerError());
	}

	/** 일곱 가지 변경이 건드리는 행 전부. */
	private List<List<Map<String, Object>>> state(long performanceId) {
		return List.of("SELECT * FROM performance WHERE id = ?",
				"SELECT * FROM seat_grade WHERE performance_id = ? ORDER BY id",
				"SELECT * FROM zone_grade WHERE performance_id = ? ORDER BY id",
				"SELECT * FROM performance_schedule WHERE performance_id = ? ORDER BY id",
				"SELECT url, sort_order FROM performance_image WHERE performance_id = ? ORDER BY sort_order")
			.stream()
			.map(sql -> jdbc.queryForList(sql, performanceId))
			.toList();
	}

	/** 담당자 토큰으로 보낸다. */
	private ResultActions send(HttpMethod method, String url, Object body) throws Exception {
		var builder = request(method, url)
			.cookie(sessionCookie(new CustomUserDetails(ACTOR, AccountType.SELLER, "STAFF", MY_BUSINESS)));
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return mockMvc.perform(builder);
	}

	private long register(Map<String, Object> body) throws Exception {
		send(HttpMethod.POST, "/api/v1/seller/performances", body).andExpect(status().isCreated());
		return jdbc.queryForObject("SELECT id FROM performance WHERE business_id = ?", Long.class, MY_BUSINESS);
	}

	private Map<String, Object> performanceBody(long venue) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("venueId", venue);
		body.put("cancelPolicyId", jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class));
		body.put("title", "감사 로그 공연");
		body.put("genre", "PLAY");
		body.put("ageLimit", 0);
		body.put("runningTime", 90);
		body.put("posterUrl", "https://cdn.example.com/posters/audit.jpg");
		body.put("images", List.of(Map.of("url", "https://cdn.example.com/details/audit.jpg", "sortOrder", 1)));
		return body;
	}

	private void putSaleFields(Map<String, Object> body) {
		body.put("saleOpenAt", FORMAT.format(daysLater(10)));
		body.put("saleCloseAt", FORMAT.format(daysLater(20)));
		body.put("maxPerPerson", 4);
		body.put("receiveType", "ONSITE");
		body.put("bookingFee", 1000);
	}

	private static Map<String, Object> zone(long zoneId, String assignmentStatus, Long gradeId) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("zoneId", zoneId);
		item.put("assignmentStatus", assignmentStatus);
		item.put("gradeId", gradeId);
		return item;
	}

	private static LocalDateTime daysLater(int days) {
		return LocalDateTime.now().plusDays(days).withHour(18).withMinute(0).withSecond(0).withNano(0);
	}

	private long insertVenue(String name) {
		return jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES (?, '주소', '테스트지역', 100, 'ACTIVE', NOW(), NOW()) RETURNING id
			""", Long.class, name);
	}

	private long insertZone(long venue, String name, int sortOrder) {
		return jdbc.queryForObject(
			"INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, ?, ?) RETURNING id",
			Long.class, venue, name, sortOrder);
	}

	private long id(String table, long performanceId) {
		return jdbc.queryForObject("SELECT id FROM " + table + " WHERE performance_id = ?", Long.class, performanceId);
	}

	private String latest(String expression) {
		return jdbc.queryForObject("SELECT " + expression + " FROM audit_log ORDER BY id DESC LIMIT 1", String.class);
	}

	private void assertLatest(long total, String targetType, long targetId, String action) {
		assertThat(count("audit_log")).isEqualTo(total);
		assertThat(jdbc.queryForMap("""
			SELECT actor_type, actor_id, business_id, target_type, target_id, action
			FROM audit_log ORDER BY id DESC LIMIT 1
			""")).containsExactlyInAnyOrderEntriesOf(Map.of("actor_type", "SELLER", "actor_id", ACTOR,
			"business_id", MY_BUSINESS, "target_type", targetType, "target_id", targetId, "action", action));
	}
}
