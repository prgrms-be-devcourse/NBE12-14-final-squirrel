package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.type.ReceiveMethod;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 예매를 만들 때 필요한 값. 공연·공연장·회원 도메인의 값을 파사드가 읽어 이 모양으로 바꿔 넘긴다.
 * 예매 서비스가 다른 도메인의 엔티티를 몰라도 되게 하려는 것이다.
 *
 * @param bookingFee 좌석 1장당 예매 수수료(원). 공연에 수수료가 없으면 0
 * @param cancelRules 예매 시점의 취소 규정 구간. 그대로 복사해 저장한다
 * @param expiresAt 결제 제한 시각
 */
public record BookingCreateCommand(
	Long userId,
	Long scheduleId,
	Long performanceId,
	ReceiveMethod receiveMethod,
	String ordererName,
	String ordererPhone,
	String ordererEmail,
	int bookingFee,
	List<SeatLine> seats,
	List<CancelRule> cancelRules,
	LocalDate today,
	LocalDateTime expiresAt
) {

	/** 예매 좌석 한 줄. 구역·열·번호·등급·가격은 예매 시점의 값을 복사한다. */
	public record SeatLine(
		Long scheduleSeatId,
		String zoneName,
		String rowLabel,
		String seatNumber,
		String gradeName,
		int price
	) {
	}

	/** 취소 규정 구간. 공연 daysFrom 일 전 이상 ~ daysTo 일 전 이하(비어 있으면 제한 없음) 구간의 수수료율이다. */
	public record CancelRule(Integer daysFrom, Integer daysTo, Integer feeRate, boolean cancelable) {
	}
}
