package com.squirrel.tickting.domain.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.squirrel.tickting.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** 예매·좌석 테이블의 마이그레이션이 설계대로 만들어졌는지(기본 데이터, 중복 방지 인덱스) 확인한다. */
class BookingSchemaTest extends IntegrationTestSupport {

	/** 로컬 시드가 만든 판매 좌석과 겹치지 않도록 이 테스트는 큰 회차 번호만 쓰고, 자기가 넣은 행만 지운다. */
	private static final long TEST_SCHEDULE_1 = 900_001L;
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
			VALUES (?, ?, 'F2구역', '9열', '6', 'R', 154000, 2000, ?)
			""", bookingId, scheduleSeatId, active);
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
}
