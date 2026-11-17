package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공연 상세. 공연장·사업체·공연·회차·좌석·등급을 SQL 로 직접 넣고 확인한다. (판매자 공연 등록 API 는 다른 파트 작업이라 기대지 않는다)
 * 시각은 DB 의 NOW() 가 아니라 자바에서 만든 값을 넣는다. 컨테이너 DB 와 애플리케이션의 시간대가 달라도 어긋나지 않게 하려는 것이다.
 * 테스트 트랜잭션 안에서 넣고 끝나면 되돌려서 시드 데이터는 그대로 남는다.
 */
@Transactional
class UserPerformanceDetailApiTest extends IntegrationTestSupport {

	private static final String URL = "/api/v1/performances/";

	private final LocalDateTime now = LocalDateTime.now().withNano(0);
	private Long venueId;
	private Long businessId;
	private Long cancelPolicyId;
	private long venueSeatSequence;
	private long zoneSequence = 900_000;

	@BeforeEach
	void setUpData() {
		cancelPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('가상 아레나', '서울 가상구 1', '서울', 100, 'ACTIVE', ?, ?) RETURNING id
			""", Long.class, now, now);
		businessId = jdbc.queryForObject("""
			INSERT INTO seller_business (company_name, representative_name, business_number, contact_phone, contact_email,
			                             terms_agreed_at, status, reject_reason, reviewed_by, reviewed_at, created_at, updated_at)
			VALUES ('(주)가상기획', '김대표', '1234567890', '01012345678', 'contact@company.com',
			        ?, 'APPROVED', NULL, 1, ?, ?, ?) RETURNING id
			""", Long.class, now, now, now, now);
		jdbc.update("UPDATE common_guide SET content = '예매 안내 본문' WHERE type = 'BOOKING_GUIDE'");
		jdbc.update("UPDATE common_guide SET content = '환불 안내 본문' WHERE type = 'REFUND_GUIDE'");
	}

	// ---- 데이터 만들기 ----

	/** 판매 오픈은 어제, 판매 종료는 30일 뒤인 공연. */
	private Long savePerformance(String status) {
		return savePerformance(status, now.minusDays(1));
	}

	private Long savePerformance(String status, LocalDateTime saleOpenAt) {
		return jdbc.queryForObject("""
			INSERT INTO performance (business_id, venue_id, created_by, cancel_policy_id, title, genre, age_limit,
			                         running_time, cast_info, poster_url, notice, guide, status, sale_open_at,
			                         sale_close_at, max_per_person, receive_type, booking_fee, created_at, updated_at)
			VALUES (?, ?, 1, ?, '가상 콘서트', 'CONCERT', 12, 150, '가상 밴드', 'https://cdn.example.com/p.png',
			        '입장 안내', '촬영 금지', ?, ?, ?, 4, 'BOTH', 2000, ?, ?)
			RETURNING id
			""", Long.class, businessId, venueId, cancelPolicyId, status, saleOpenAt, now.plusDays(30), now, now);
	}

	private void saveImage(Long performanceId, String url, int sortOrder) {
		jdbc.update("INSERT INTO performance_image (performance_id, url, sort_order) VALUES (?, ?, ?)",
			performanceId, url, sortOrder);
	}

	private Long saveSchedule(Long performanceId, LocalDateTime startAt, String status) {
		return jdbc.queryForObject("""
			INSERT INTO performance_schedule (performance_id, start_at, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, ?) RETURNING id
			""", Long.class, performanceId, startAt, status, now, now);
	}

	/** 한 회차 안에서 같은 공연장 좌석이 두 번 나올 수 없어서(UNIQUE) 좌석 번호를 매번 바꾼다. */
	private void saveSeat(Long scheduleId, String status) {
		jdbc.update("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, status, version, updated_at)
			VALUES (?, ?, 1, ?, 0, ?)
			""", scheduleId, ++venueSeatSequence, status, now);
	}

	private Long saveGrade(Long performanceId, String name, int price) {
		return jdbc.queryForObject("""
			INSERT INTO seat_grade (performance_id, name, price, color) VALUES (?, ?, ?, '#E8503A') RETURNING id
			""", Long.class, performanceId, name, price);
	}

	/** 한 공연에서 한 구역은 한 번만 지정할 수 있어서(UNIQUE) 구역 번호를 매번 바꾼다. */
	private void assignZone(Long performanceId, Long gradeId) {
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, grade_id, assignment_status) VALUES (?, ?, ?, 'ASSIGNED')
			""", performanceId, ++zoneSequence, gradeId);
	}

	private void leaveZoneUnsold(Long performanceId) {
		jdbc.update("""
			INSERT INTO zone_grade (performance_id, zone_id, grade_id, assignment_status)
			VALUES (?, ?, NULL, 'INTENTIONALLY_UNSOLD')
			""", performanceId, ++zoneSequence);
	}

	/** 응답의 일시는 초까지 찍힌다. LocalDateTime.toString() 은 0초일 때 초를 빼서 1분에 한 번꼴로 어긋나므로 초까지 맞춰 비교한다. */
	private static String iso(LocalDateTime time) {
		return time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
	}

	// ---- 테스트 ----

	@Test
	@DisplayName("로그인 없이 공개된 공연의 상세를 보고, 공연·공연장·판매 설정·공통 안내·판매자 정보가 함께 온다")
	void detailWithoutLogin() throws Exception {
		Long id = savePerformance("PUBLISHED");
		saveImage(id, "https://cdn.example.com/d2.png", 2);
		saveImage(id, "https://cdn.example.com/d1.png", 1);

		mockMvc.perform(get(URL + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.performanceId").value(id))
			.andExpect(jsonPath("$.data.title").value("가상 콘서트"))
			.andExpect(jsonPath("$.data.genre").value("CONCERT"))
			.andExpect(jsonPath("$.data.ageLimit").value(12))
			.andExpect(jsonPath("$.data.runningTime").value(150))
			.andExpect(jsonPath("$.data.castInfo").value("가상 밴드"))
			.andExpect(jsonPath("$.data.posterUrl").value("https://cdn.example.com/p.png"))
			.andExpect(jsonPath("$.data.images[0].url").value("https://cdn.example.com/d1.png"))
			.andExpect(jsonPath("$.data.images[0].sortOrder").value(1))
			.andExpect(jsonPath("$.data.images[1].sortOrder").value(2))
			.andExpect(jsonPath("$.data.notice").value("입장 안내"))
			.andExpect(jsonPath("$.data.guide").value("촬영 금지"))
			.andExpect(jsonPath("$.data.venue.venueId").value(venueId))
			.andExpect(jsonPath("$.data.venue.name").value("가상 아레나"))
			.andExpect(jsonPath("$.data.venue.address").value("서울 가상구 1"))
			.andExpect(jsonPath("$.data.venue.region").value("서울"))
			.andExpect(jsonPath("$.data.sale.saleOpenAt").value(iso(now.minusDays(1))))
			.andExpect(jsonPath("$.data.sale.saleCloseAt").value(iso(now.plusDays(30))))
			.andExpect(jsonPath("$.data.sale.maxPerPerson").value(4))
			.andExpect(jsonPath("$.data.sale.receiveType").value("BOTH"))
			.andExpect(jsonPath("$.data.sale.bookingFee").value(2000))
			.andExpect(jsonPath("$.data.commonGuide.bookingGuide").value("예매 안내 본문"))
			.andExpect(jsonPath("$.data.commonGuide.refundGuide").value("환불 안내 본문"))
			.andExpect(jsonPath("$.data.seller.companyName").value("(주)가상기획"))
			.andExpect(jsonPath("$.data.seller.representativeName").value("김대표"))
			.andExpect(jsonPath("$.data.seller.businessNumber").value("1234567890"))
			.andExpect(jsonPath("$.data.seller.contactPhone").value("01012345678"))
			.andExpect(jsonPath("$.data.seller.contactEmail").value("contact@company.com"))
			// 사업체 상태·심사 정보는 예매자에게 보여 주지 않는다
			.andExpect(jsonPath("$.data.seller.status").doesNotExist())
			.andExpect(jsonPath("$.data.seller.reviewedBy").doesNotExist());
	}

	@Test
	@DisplayName("작성 중(DRAFT)·취소(CANCELED) 공연과 없는 공연은 404 PERFORMANCE000 이다")
	void notPublishedIsNotFound() throws Exception {
		Long draft = savePerformance("DRAFT");
		Long canceled = savePerformance("CANCELED");

		for (Long id : List.of(draft, canceled, 999_999_999L)) {
			mockMvc.perform(get(URL + id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.code").value("PERFORMANCE000"));
		}
	}

	@Test
	@DisplayName("회차는 취소된 회차를 빼고 일시가 빠른 순이며, 회차마다 판매 상태를 계산한다")
	void schedulesWithSaleStatus() throws Exception {
		Long id = savePerformance("PUBLISHED");
		Long ended = saveSchedule(id, now.minusDays(1), "ON_SALE");
		saveSeat(ended, "AVAILABLE");
		Long soldOut = saveSchedule(id, now.plusDays(3), "ON_SALE");
		saveSeat(soldOut, "SOLD");
		saveSeat(soldOut, "HELD");
		Long onSale = saveSchedule(id, now.plusDays(2), "ON_SALE");
		saveSeat(onSale, "AVAILABLE");
		saveSchedule(id, now.plusDays(1), "CANCELED");

		mockMvc.perform(get(URL + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.schedules.length()").value(3))
			.andExpect(jsonPath("$.data.schedules[0].scheduleId").value(ended))
			.andExpect(jsonPath("$.data.schedules[0].startAt").value(iso(now.minusDays(1))))
			.andExpect(jsonPath("$.data.schedules[0].saleStatus").value("ENDED"))
			.andExpect(jsonPath("$.data.schedules[1].scheduleId").value(onSale))
			.andExpect(jsonPath("$.data.schedules[1].saleStatus").value("ON_SALE"))
			.andExpect(jsonPath("$.data.schedules[2].scheduleId").value(soldOut))
			.andExpect(jsonPath("$.data.schedules[2].saleStatus").value("SOLD_OUT"));
	}

	@Test
	@DisplayName("판매 오픈 전이면 좌석이 아직 없어도 회차는 판매 예정이고, 일시 중지·연기 회차는 회차 상태로 구분된다")
	void upcomingAndPausedSchedules() throws Exception {
		Long id = savePerformance("PUBLISHED", now.plusDays(1));
		saveSchedule(id, now.plusDays(5), "ON_SALE");
		saveSchedule(id, now.plusDays(6), "PAUSED");
		saveSchedule(id, now.plusDays(7), "POSTPONED");

		mockMvc.perform(get(URL + id))
			.andExpect(jsonPath("$.data.schedules.length()").value(3))
			.andExpect(jsonPath("$.data.schedules[0].status").value("ON_SALE"))
			.andExpect(jsonPath("$.data.schedules[0].saleStatus").value("UPCOMING"))
			.andExpect(jsonPath("$.data.schedules[1].status").value("PAUSED"))
			.andExpect(jsonPath("$.data.schedules[1].saleStatus").value("UPCOMING"))
			.andExpect(jsonPath("$.data.schedules[2].status").value("POSTPONED"));
	}

	@Test
	@DisplayName("등급은 구역에 지정된 것만 비싼 순으로 한 번씩 나온다")
	void onlyAssignedGrades() throws Exception {
		Long id = savePerformance("PUBLISHED");
		Long rGrade = saveGrade(id, "R석", 154_000);
		Long vipGrade = saveGrade(id, "VIP석", 165_000);
		saveGrade(id, "S석", 132_000);
		assignZone(id, rGrade);
		assignZone(id, rGrade);
		assignZone(id, vipGrade);
		leaveZoneUnsold(id);

		mockMvc.perform(get(URL + id))
			.andExpect(jsonPath("$.data.grades.length()").value(2))
			.andExpect(jsonPath("$.data.grades[0].gradeId").value(vipGrade))
			.andExpect(jsonPath("$.data.grades[0].name").value("VIP석"))
			.andExpect(jsonPath("$.data.grades[0].price").value(165_000))
			.andExpect(jsonPath("$.data.grades[0].color").value("#E8503A"))
			.andExpect(jsonPath("$.data.grades[1].gradeId").value(rGrade));
	}

	@Test
	@DisplayName("취소 규정은 공연이 고른 규정의 구간을 공연에서 먼 구간부터 보여 준다")
	void cancelPolicyRules() throws Exception {
		Long id = savePerformance("PUBLISHED");

		String body = mockMvc.perform(get(URL + id)).andReturn().getResponse().getContentAsString();

		List<Integer> daysFrom = JsonPath.read(body, "$.data.cancelPolicy[*].daysFrom");
		List<Integer> expected = jdbc.queryForList(
			"SELECT days_from FROM cancel_policy_rule WHERE policy_id = ? ORDER BY days_from DESC",
			Integer.class, cancelPolicyId);
		assertThat(daysFrom).isNotEmpty().containsExactlyElementsOf(expected);
		assertThat(JsonPath.<List<Boolean>>read(body, "$.data.cancelPolicy[*].cancelable")).contains(true, false);
	}

	@Test
	@DisplayName("공연장·판매자 사업체·공통 안내가 없어도 상세는 보이고 그 부분만 null 이다")
	void missingRelatedDataIsNull() throws Exception {
		Long id = savePerformance("PUBLISHED");
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
		jdbc.update("DELETE FROM seller_business WHERE id = ?", businessId);
		jdbc.update("DELETE FROM common_guide WHERE type = 'REFUND_GUIDE'");

		mockMvc.perform(get(URL + id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.title").value("가상 콘서트"))
			.andExpect(jsonPath("$.data.venue").isEmpty())
			.andExpect(jsonPath("$.data.seller").isEmpty())
			.andExpect(jsonPath("$.data.commonGuide.bookingGuide").value("예매 안내 본문"))
			.andExpect(jsonPath("$.data.commonGuide.refundGuide").isEmpty());
	}

	@Test
	@DisplayName("공연 번호가 숫자가 아니면 400 COMMON000 이다")
	void invalidPerformanceId() throws Exception {
		mockMvc.perform(get(URL + "abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON000"));
	}
}
