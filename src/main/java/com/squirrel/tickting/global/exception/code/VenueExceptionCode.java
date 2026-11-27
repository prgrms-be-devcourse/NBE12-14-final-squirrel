package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 공연장 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum VenueExceptionCode implements ExceptionCode {

	/** 공연을 등록하려는 공연장이 없음 */
	VENUE_NOT_FOUND("VENUE000", HttpStatus.NOT_FOUND, "존재하지 않는 공연장입니다."),
	/**
	 * 사용 중지(INACTIVE)된 공연장에서 새 판매를 열려 함. 공연 등록·공연장 변경·공개·회차 추가가 쓴다.
	 * 이미 연 판매의 관리(예매·결제·좌석 생성·회차 수정·재개 등)는 막지 않는다
	 */
	VENUE_INACTIVE("VENUE001", HttpStatus.BAD_REQUEST, "사용이 중지된 공연장입니다."),
	/** 이미 사용 중지된 공연장을 새 일정만 막는 방식(BLOCK_NEW)으로 다시 중지하려 함(ADM-11). 동시에 두 번 눌러도 이 코드다 */
	VENUE_ALREADY_INACTIVE("VENUE002", HttpStatus.CONFLICT, "이미 사용이 중지된 공연장입니다."),
	/** 이미 사용 중인 공연장을 재개하려 함(ADM-12) */
	VENUE_ALREADY_ACTIVE("VENUE003", HttpStatus.CONFLICT, "이미 사용 중인 공연장입니다."),
	/** 이미 중지된 공연장에 전부 취소(CANCEL_ALL)를 했는데 취소할 회차가 하나도 없음(ADM-11) */
	VENUE_NOTHING_TO_CANCEL("VENUE004", HttpStatus.CONFLICT, "취소할 회차가 없습니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
