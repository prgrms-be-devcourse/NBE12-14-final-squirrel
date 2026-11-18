package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.SeatGradeRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ZoneGradeRepository;
import com.squirrel.tickting.domain.performance.service.GradeService;
import com.squirrel.tickting.global.security.AccountType;
import com.squirrel.tickting.global.security.CustomUserDetails;
import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

/** 구역 설정을 만들다 실패하면 공연·이미지도 함께 취소되는지 본다. 구역 초기화만 실패하도록 바꿔 둔 별도 클래스다. */
@Import(SellerPerformanceCreateRollbackTest.FailingGradeConfig.class)
class SellerPerformanceCreateRollbackTest extends IntegrationTestSupport {

	private static final long MY_BUSINESS = 793L;

	/**
	 * 구역 초기화를 항상 실패시키는 등급 서비스로 바꿔 끼운다. (Mockito 없이 실패 상황을 만든다)
	 * 이 테스트는 공연 등록만 부르므로 등급 수정에만 쓰는 락·트랜잭션·설정값은 넘기지 않는다.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class FailingGradeConfig {

		@Bean
		@Primary
		GradeService failingGradeService(SeatGradeRepository seatGradeRepository,
			ZoneGradeRepository zoneGradeRepository, PerformanceRepository performanceRepository,
			PerformanceScheduleRepository performanceScheduleRepository) {
			return new GradeService(seatGradeRepository, zoneGradeRepository, performanceRepository,
				performanceScheduleRepository, null, null, null) {
				@Override
				public void initializeZones(Performance performance, List<Long> zoneIds) {
					throw new IllegalStateException("구역 설정 저장 실패");
				}
			};
		}
	}

	private Long venueId;

	@AfterEach
	void deleteInsertedRows() {
		String mine = "SELECT id FROM performance WHERE business_id = " + MY_BUSINESS;
		jdbc.update("DELETE FROM performance_image WHERE performance_id IN (" + mine + ")");
		jdbc.update("DELETE FROM performance WHERE business_id = ?", MY_BUSINESS);
		jdbc.update("DELETE FROM venue_zone WHERE venue_id = ?", venueId);
		jdbc.update("DELETE FROM venue WHERE id = ?", venueId);
	}

	@Test
	@DisplayName("구역 설정 저장이 실패하면 공연과 이미지도 저장되지 않는다")
	void zoneFailureRollsBackPerformance() throws Exception {
		venueId = jdbc.queryForObject("""
			INSERT INTO venue (name, address, region, total_seats, status, created_at, updated_at)
			VALUES ('롤백 테스트 공연장', '주소', '테스트지역', 100, 'ACTIVE', NOW(), NOW())
			RETURNING id
			""", Long.class);
		jdbc.update("INSERT INTO venue_zone (venue_id, name, sort_order) VALUES (?, 'A구역', 1)", venueId);
		Long defaultPolicyId = jdbc.queryForObject(
			"SELECT id FROM cancel_policy WHERE business_id IS NULL ORDER BY id LIMIT 1", Long.class);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("venueId", venueId);
		body.put("cancelPolicyId", defaultPolicyId);
		body.put("title", "롤백 공연");
		body.put("genre", "PLAY");
		body.put("ageLimit", 0);
		body.put("runningTime", 90);
		body.put("posterUrl", "https://cdn.example.com/posters/rollback.jpg");
		body.put("images", List.of(Map.of("url", "https://cdn.example.com/details/rollback.jpg", "sortOrder", 1)));

		String token = "Bearer " + jwtProvider.createAccessToken(
			new CustomUserDetails(1L, AccountType.SELLER, "OWNER", MY_BUSINESS));
		mockMvc.perform(post("/api/v1/seller/performances")
				.header("Authorization", token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body)))
			.andExpect(status().isInternalServerError());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance WHERE business_id = ?", Integer.class,
			MY_BUSINESS)).isZero();
		assertThat(jdbc.queryForObject("""
			SELECT COUNT(*) FROM performance_image WHERE url = 'https://cdn.example.com/details/rollback.jpg'
			""", Integer.class)).isZero();
	}
}
