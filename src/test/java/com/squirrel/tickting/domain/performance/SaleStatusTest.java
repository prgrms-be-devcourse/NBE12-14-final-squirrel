package com.squirrel.tickting.domain.performance;

import static org.assertj.core.api.Assertions.assertThat;

import com.squirrel.tickting.domain.performance.type.SaleStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 판매 상태 계산. 시각 경계(정각)에서 어느 쪽으로 넘어가는지를 확인한다. */
class SaleStatusTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);
	private static final LocalDateTime OPEN = NOW.minusDays(1);
	private static final LocalDateTime CLOSE = NOW.plusDays(10);
	private static final LocalDateTime LAST_START = NOW.plusDays(10).plusHours(7);

	@Test
	@DisplayName("판매 오픈 1초 전이면 판매 예정이다")
	void upcomingBeforeOpen() {
		assertThat(SaleStatus.of(NOW.plusSeconds(1), CLOSE, LAST_START, true, NOW)).isEqualTo(SaleStatus.UPCOMING);
	}

	@Test
	@DisplayName("판매 오픈 정각부터 판매 중이다")
	void onSaleAtOpen() {
		assertThat(SaleStatus.of(NOW, CLOSE, LAST_START, true, NOW)).isEqualTo(SaleStatus.ON_SALE);
	}

	@Test
	@DisplayName("판매 중인데 남은 좌석이 없으면 매진이다")
	void soldOut() {
		assertThat(SaleStatus.of(OPEN, CLOSE, LAST_START, false, NOW)).isEqualTo(SaleStatus.SOLD_OUT);
	}

	@Test
	@DisplayName("오픈 전에는 좌석이 아직 없어도 매진이 아니라 판매 예정이다")
	void upcomingEvenWithoutSeats() {
		assertThat(SaleStatus.of(NOW.plusDays(1), CLOSE, LAST_START, false, NOW)).isEqualTo(SaleStatus.UPCOMING);
	}

	@Test
	@DisplayName("판매 종료 시각 정각이 되면 좌석이 남아 있어도 종료다")
	void endedAtSaleClose() {
		assertThat(SaleStatus.of(OPEN, NOW, LAST_START, true, NOW)).isEqualTo(SaleStatus.ENDED);
	}

	@Test
	@DisplayName("마지막 회차가 시작되면 종료다")
	void endedAtLastStart() {
		assertThat(SaleStatus.of(OPEN, CLOSE, NOW, true, NOW)).isEqualTo(SaleStatus.ENDED);
	}

	@Test
	@DisplayName("취소되지 않은 회차가 하나도 없으면 살 수 있는 회차가 없으니 종료다")
	void endedWithoutSchedule() {
		assertThat(SaleStatus.of(OPEN, CLOSE, null, false, NOW)).isEqualTo(SaleStatus.ENDED);
	}
}
