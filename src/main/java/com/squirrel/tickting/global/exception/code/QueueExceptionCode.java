package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 대기열 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum QueueExceptionCode implements ExceptionCode {

	/** 이 회차의 대기열에서 입장하지 않았거나 입장 시간이 지났다. 구역 요약·구역 좌석 조회와 좌석 점유에서 쓴다 */
	NOT_ADMITTED("QUEUE000", HttpStatus.FORBIDDEN, "대기열 입장이 필요합니다. 예매하기를 눌러 입장해 주세요.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
