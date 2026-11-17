package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 예매(좌석 점유·예매 생성) 관련 에러 코드. */
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
	SEAT_NOT_FOUND("BOOKING005", HttpStatus.NOT_FOUND, "존재하지 않는 좌석입니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
