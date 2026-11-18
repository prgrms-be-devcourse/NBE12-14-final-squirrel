package com.squirrel.tickting.domain.performance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.time.LocalDateTime;
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

	// ---- 테스트 ----

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
}
