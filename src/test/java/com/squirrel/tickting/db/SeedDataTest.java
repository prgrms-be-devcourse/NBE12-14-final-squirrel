package com.squirrel.tickting.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 로컬 시드(db/seed)가 설계대로 들어가는지 확인한다.
 * 프로필을 주지 않으면 local 이 켜져서 테스트에도 시드가 적용된다. 마이그레이션을 바꾸면 시드도 같이 깨지는지 여기서 알 수 있다.
 */
class SeedDataTest extends IntegrationTestSupport {

	private static final String ARENA = "가상 아레나 (샘플)";
	private static final String SMALL_THEATER = "소극장 (테스트용)";
	private static final String CONCERT = "가상 콘서트 (샘플)";
	private static final String PLAY = "작은 연극 (샘플)";
	private static final String UPCOMING_MUSICAL = "곧 오픈하는 뮤지컬 (샘플)";

	private List<Long> scheduleIds(String title) {
		return jdbc.queryForList("""
			SELECT s.id FROM performance_schedule s
			JOIN performance p ON p.id = s.performance_id
			WHERE p.title = ?
			ORDER BY s.start_at
			""", Long.class, title);
	}

	private Map<String, Long> seatCountByGrade(long scheduleId) {
		return jdbc.queryForList("""
				SELECT g.name AS grade, COUNT(*) AS cnt
				FROM schedule_seat ss
				JOIN seat_grade g ON g.id = ss.grade_id
				WHERE ss.schedule_id = ?
				GROUP BY g.name
				""", scheduleId).stream()
			.collect(Collectors.toMap(row -> (String) row.get("grade"), row -> (Long) row.get("cnt")));
	}

	@Test
	@DisplayName("공연장 2곳이 들어 있고, 총 좌석 수가 실제 좌석 수와 같다 (가상 아레나 3,022석, 소극장 30석)")
	void venuesHaveSeats() {
		List<Map<String, Object>> venues = jdbc.queryForList("""
			SELECT v.name, v.total_seats, COUNT(vs.id) AS seat_count
			FROM venue v LEFT JOIN venue_seat vs ON vs.venue_id = v.id
			GROUP BY v.id ORDER BY v.id
			""");

		assertThat(venues).extracting(v -> v.get("name")).containsExactly(ARENA, SMALL_THEATER);
		assertThat(venues).extracting(v -> v.get("seat_count")).containsExactly(3022L, 30L);
		assertThat(venues).extracting(v -> ((Number) v.get("total_seats")).longValue()).containsExactly(3022L, 30L);
	}

	@Test
	@DisplayName("가상 아레나는 22개 구역이고, 구역마다 표시 순서(sort_order)가 1부터 빠짐없이 매겨진다")
	void zonesAndSeatOrder() {
		Long arenaZones = jdbc.queryForObject("""
			SELECT COUNT(*) FROM venue_zone z JOIN venue v ON v.id = z.venue_id WHERE v.name = ?
			""", Long.class, ARENA);
		Long brokenOrder = jdbc.queryForObject("""
			SELECT COUNT(*) FROM (
			    SELECT zone_id FROM venue_seat GROUP BY zone_id
			    HAVING MIN(sort_order) <> 1 OR MAX(sort_order) <> COUNT(*)
			) broken
			""", Long.class);

		assertThat(arenaZones).isEqualTo(22);
		assertThat(brokenOrder).isZero();
	}

	@Test
	@DisplayName("가상 콘서트는 회차 2개에 판매 좌석이 3,022석씩 있고, 등급별 좌석 수가 문서와 같다")
	void concertSeatsAreGenerated() {
		List<Long> schedules = scheduleIds(CONCERT);

		assertThat(schedules).hasSize(2);
		for (Long scheduleId : schedules) {
			assertThat(seatCountByGrade(scheduleId))
				.containsEntry("VIP석", 500L)
				.containsEntry("R석", 568L)
				.containsEntry("S석", 1196L)
				.containsEntry("A석", 758L);
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_seat WHERE schedule_id = ?",
				Long.class, scheduleId)).isEqualTo(3022L);
			assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM schedule_seat WHERE schedule_id = ? AND status <> 'AVAILABLE'
				""", Long.class, scheduleId)).isZero();
		}
	}

	@Test
	@DisplayName("작은 연극은 30석이 모두 판매 가능이고, 구역마다 등급 가격이 복사되어 있다")
	void playSeatsAreAvailable() {
		Long scheduleId = scheduleIds(PLAY).get(0);

		List<Map<String, Object>> prices = jdbc.queryForList("""
			SELECT price, COUNT(*) AS cnt FROM schedule_seat WHERE schedule_id = ? AND status = 'AVAILABLE'
			GROUP BY price ORDER BY price DESC
			""", scheduleId);

		assertThat(prices).extracting(p -> ((Number) p.get("price")).intValue()).containsExactly(30000, 20000, 10000);
		assertThat(prices).extracting(p -> p.get("cnt")).containsExactly(10L, 10L, 10L);
	}

	@Test
	@DisplayName("곧 오픈하는 뮤지컬 회차는 판매 좌석이 아직 없고 좌석 생성 시각도 비어 있다")
	void upcomingMusicalHasNoSeatsYet() {
		Long scheduleId = scheduleIds(UPCOMING_MUSICAL).get(0);

		assertThat(jdbc.queryForObject("SELECT seats_generated_at FROM performance_schedule WHERE id = ?",
			Timestamp.class, scheduleId)).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedule_seat WHERE schedule_id = ?",
			Long.class, scheduleId)).isZero();
	}

	@Test
	@DisplayName("공연은 공개 3개와 작성 중 1개이고, 모두 기본 취소 규정을 가리킨다")
	void performancesAndCancelPolicy() {
		List<Map<String, Object>> statuses = jdbc.queryForList(
			"SELECT status, COUNT(*) AS cnt FROM performance GROUP BY status ORDER BY status");
		Long withoutPolicy = jdbc.queryForObject("""
			SELECT COUNT(*) FROM performance p
			WHERE NOT EXISTS (SELECT 1 FROM cancel_policy c WHERE c.id = p.cancel_policy_id AND c.business_id IS NULL)
			""", Long.class);

		assertThat(statuses).extracting(s -> s.get("status")).containsExactly("DRAFT", "PUBLISHED");
		assertThat(statuses).extracting(s -> s.get("cnt")).containsExactly(1L, 3L);
		assertThat(withoutPolicy).isZero();
	}

	@Test
	@DisplayName("공통 안내(예매·환불)가 한 행씩 들어 있다")
	void commonGuides() {
		List<String> types = jdbc.queryForList("SELECT type FROM common_guide ORDER BY type", String.class);

		assertThat(types).containsExactly("BOOKING_GUIDE", "REFUND_GUIDE");
	}
}
