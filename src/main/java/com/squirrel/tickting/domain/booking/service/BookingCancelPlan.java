package com.squirrel.tickting.domain.booking.service;

import java.util.List;

/**
 * 예매 취소에서 무엇을 얼마에 취소할지 계산한 결과. 환불 금액이 정해져야 환불 행을 만들 수 있고, 환불 번호가 있어야
 * 좌석에 환불 번호를 적을 수 있어서, 계산(planCancel)과 반영(applyCancel) 사이에 파사드가 환불을 만든다.
 *
 * @param refundAmount  이번에 돌려줄 금액 = Σ(좌석 가격 − 취소 수수료) + Σ(예매 수수료)
 * @param fullyCanceled 이번 취소로 남는 활성 좌석이 없으면 true (예매·결제가 전체 취소가 된다)
 * @param postponed     공연 연기에 따른 무료 취소면 true (환불 사유가 달라진다)
 */
public record BookingCancelPlan(
	Long bookingId,
	int feeRate,
	List<Line> lines,
	int cancelFeeTotal,
	int refundAmount,
	boolean fullyCanceled,
	boolean postponed
) {

	/** 취소할 좌석 한 장. 판매 좌석 번호는 끝난 회차의 좌석을 지웠으면 비어 있다. */
	public record Line(Long bookingSeatId, Long scheduleSeatId, int cancelFee) {
	}

	public List<Long> bookingSeatIds() {
		return lines.stream().map(Line::bookingSeatId).toList();
	}
}
