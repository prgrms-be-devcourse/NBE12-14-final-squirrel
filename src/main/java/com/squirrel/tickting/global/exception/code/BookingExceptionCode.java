package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 예매(좌석 점유·예매 생성·예매 취소) 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum BookingExceptionCode implements ExceptionCode {

	/**
	 * 요청한 좌석 중 하나라도 이미 점유되었거나 판매됨. 일부만 잡지 않고 전체를 거절한다.
	 * 만료된 점유도 스케줄러가 풀어 줄 때까지는 이 코드다
	 */
	SEAT_ALREADY_TAKEN("BOOKING000", HttpStatus.CONFLICT, "이미 선택된 좌석이 있습니다. 다른 좌석을 선택해 주세요."),
	/** 이미 가진 좌석(결제 대기 포함)과 요청 좌석을 더하면 공연의 1인 최대 매수를 넘음 */
	MAX_PER_PERSON_EXCEEDED("BOOKING001", HttpStatus.CONFLICT, "한 공연에서 예매할 수 있는 좌석 수를 넘었습니다."),
	/** 공연이 공개 상태가 아니거나 판매 기간 밖이거나, 회차가 중지·연기·취소되었거나 이미 시작함 */
	SCHEDULE_NOT_ON_SALE("BOOKING002", HttpStatus.BAD_REQUEST, "예매할 수 없는 회차입니다."),
	/** 판매 제외(BLOCKED)·판매자 보류(RESERVED) 좌석 */
	SEAT_NOT_SELLABLE("BOOKING003", HttpStatus.BAD_REQUEST, "판매하지 않는 좌석입니다."),
	/** 없는 회차이거나 공개되지 않은 공연의 회차. 존재 여부를 알려주지 않으려고 같은 404로 답한다 */
	SCHEDULE_NOT_FOUND("BOOKING004", HttpStatus.NOT_FOUND, "존재하지 않는 회차입니다."),
	/** 없는 좌석이거나 다른 회차의 좌석 */
	SEAT_NOT_FOUND("BOOKING005", HttpStatus.NOT_FOUND, "존재하지 않는 좌석입니다."),
	/** 없는 예매이거나 남의 예매. 번호가 존재하는지 알려주지 않으려고 같은 404로 답한다 */
	BOOKING_NOT_FOUND("BOOKING006", HttpStatus.NOT_FOUND, "존재하지 않는 예매입니다."),
	/** 결제 대기(PENDING)가 아니거나 결제 제한 시각이 지났거나 공연이 더는 공개 상태가 아님 */
	BOOKING_NOT_PAYABLE("BOOKING007", HttpStatus.CONFLICT, "결제할 수 없는 예매입니다."),
	/** 결제 완료(PAID)·일부 취소(PARTIAL_CANCELED)가 아님. 결제 대기·만료·이미 전체 취소된 예매 */
	BOOKING_NOT_CANCELABLE("BOOKING008", HttpStatus.CONFLICT, "취소할 수 없는 예매입니다."),
	/** 취소 규정상 지금은 취소할 수 없음. 공연 당일처럼 취소 불가 구간이거나 회차가 이미 시작함 */
	CANCEL_PERIOD_OVER("BOOKING009", HttpStatus.BAD_REQUEST, "지금은 취소할 수 없는 기간입니다."),
	/** 이미 취소된 좌석이거나 이 예매의 좌석이 아님 */
	SEAT_NOT_CANCELABLE("BOOKING010", HttpStatus.BAD_REQUEST, "취소할 수 없는 좌석이 포함되어 있습니다."),
	/** 판매가 일시 중지된 회차를 점유·결제하거나 대기열에 새로 들어가려 함. 화면은 이 코드를 보고 알린 뒤 공연 상세로 보낸다 */
	SCHEDULE_SALE_PAUSED("BOOKING012", HttpStatus.CONFLICT, "판매가 일시 중지된 회차입니다."),
	/**
	 * 일정 미정으로 연기된 회차를 점유하거나 대기열에 서려 함(대기 중이던 사람 포함). 결제는 미정인 회차와,
	 * 연기하기 전에 만든 결제 대기 예매를 막고 메시지를 바꿔 쓴다
	 */
	SCHEDULE_POSTPONED("BOOKING013", HttpStatus.CONFLICT, "연기된 회차라 지금은 예매할 수 없습니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
