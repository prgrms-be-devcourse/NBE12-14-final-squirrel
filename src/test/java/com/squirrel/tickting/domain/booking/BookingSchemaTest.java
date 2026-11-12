package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.support.IntegrationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** 예매·좌석 테이블의 마이그레이션이 설계대로 만들어졌는지(기본 데이터, 중복 방지 인덱스) 확인한다. */
class BookingSchemaTest extends IntegrationTestSupport {

	/** 로컬 시드가 만든 판매 좌석과 겹치지 않도록 이 테스트는 큰 회차 번호만 쓰고, 자기가 넣은 행만 지운다. */
	private static final long TEST_SCHEDULE_1 = 900_001L;
	private static final long TEST_SCHEDULE_2 = 900_002L;

	@BeforeEach
	@AfterEach
	void cleanTestRows() {
		jdbc.update("DELETE FROM booking_seat");
		jdbc.update("DELETE FROM payment");
		jdbc.update("DELETE FROM refund");
		jdbc.update("DELETE FROM schedule_seat WHERE schedule_id >= ?", TEST_SCHEDULE_1);
	}

	private void insertBookingSeat(long bookingId, Long scheduleSeatId, boolean active) {
		jdbc.update("""
			INSERT INTO booking_seat (booking_id, schedule_seat_id, zone_name, row_label, seat_number,
			                          grade_name, price, booking_fee, active)
			VALUES (?, ?, 'F2구역', '9열', '6', 'R석', 154000, 2000, ?)
			""", bookingId, scheduleSeatId, active);
	}

	private void insertScheduleSeat(long scheduleId, long venueSeatId) {
		jdbc.update("""
			INSERT INTO schedule_seat (schedule_id, venue_seat_id, zone_id, status, version, updated_at)
			VALUES (?, ?, 1, 'AVAILABLE', 0, now())
			""", scheduleId, venueSeatId);
	}

	@Test
	@DisplayName("서비스 기본 취소 규정이 4개 구간(7일 전 0%, 6~3일 10%, 2~1일 20%, 당일 취소 불가)으로 들어 있다")
	void defaultCancelPolicyExists() {
		List<Map<String, Object>> rules = jdbc.queryForList("""
			SELECT r.days_from, r.days_to, r.fee_rate, r.cancelable
			FROM cancel_policy_rule r
			JOIN cancel_policy p ON p.id = r.policy_id
			WHERE p.business_id IS NULL
			ORDER BY r.days_from DESC
			""");

		assertThat(rules).hasSize(4);
		assertThat(rules).extracting(rule -> rule.get("fee_rate")).containsExactly(0, 10, 20, 0);
		assertThat(rules).extracting(rule -> rule.get("cancelable")).containsExactly(true, true, true, false);
		assertThat(rules.get(0).get("days_to")).isNull();
	}

	@Test
	@DisplayName("같은 판매 좌석을 활성 예매 좌석으로 두 번 넣을 수 없다 (중복 판매의 마지막 방어선)")
	void activeBookingSeatIsUniquePerScheduleSeat() {
		insertBookingSeat(1, 100L, true);

		assertThatThrownBy(() -> insertBookingSeat(2, 100L, true))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uq_booking_seat_active");
	}

	@Test
	@DisplayName("취소해서 비활성이 된 좌석은 다시 다른 예매에 활성으로 넣을 수 있다")
	void canceledSeatCanBeSoldAgain() {
		insertBookingSeat(1, 100L, true);
		jdbc.update("UPDATE booking_seat SET active = false WHERE booking_id = 1");

		insertBookingSeat(2, 100L, true);

		assertThat(count("booking_seat")).isEqualTo(2);
	}

	@Test
	@DisplayName("끝난 회차의 판매 좌석을 지워 번호가 비어 있는 예매 좌석은 여러 개여도 된다")
	void bookingSeatWithoutScheduleSeatIsAllowed() {
		insertBookingSeat(1, null, true);
		insertBookingSeat(2, null, true);

		assertThat(count("booking_seat")).isEqualTo(2);
	}

	@Test
	@DisplayName("같은 회차에 같은 공연장 좌석으로 판매 좌석을 두 번 만들 수 없다")
	void scheduleSeatIsUniquePerVenueSeat() {
		insertScheduleSeat(TEST_SCHEDULE_1, 10);

		assertThatThrownBy(() -> insertScheduleSeat(TEST_SCHEDULE_1, 10))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uq_schedule_seat_venue_seat");

		insertScheduleSeat(TEST_SCHEDULE_2, 10);
	}

	@Test
	@DisplayName("결제 키는 승인 전에는 비어 있어도 여러 건이 가능하고, 주문 ID와 환불 중복 방지 키는 중복될 수 없다")
	void paymentAndRefundKeysAreUnique() {
		String insertPayment = """
			INSERT INTO payment (booking_id, order_id, payment_key, amount, status, created_at)
			VALUES (1, ?, ?, 312000, 'READY', now())
			""";
		jdbc.update(insertPayment, "order-1", null);
		jdbc.update(insertPayment, "order-2", null);

		assertThatThrownBy(() -> jdbc.update(insertPayment, "order-1", null))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uq_payment_order_id");

		String insertRefund = """
			INSERT INTO refund (payment_id, booking_id, amount, reason_type, idempotency_key, status,
			                    retry_count, requested_at)
			VALUES (1, 1, 1000, 'USER_CANCEL', ?, 'REQUESTED', 0, now())
			""";
		jdbc.update(insertRefund, "key-1");

		assertThatThrownBy(() -> jdbc.update(insertRefund, "key-1"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("uq_refund_idempotency_key");
	}
}
