package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 공연 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum PerformanceExceptionCode implements ExceptionCode {

	/** 없는 공연이거나 다른 사업체의 공연. 번호가 존재하는지 알려주지 않으려고 둘 다 같은 404로 답한다 */
	PERFORMANCE_NOT_FOUND("PERFORMANCE000", HttpStatus.NOT_FOUND, "존재하지 않는 공연입니다."),
	/** 판매 오픈·종료를 둘 다 넣었는데 종료가 오픈보다 늦지 않음(같은 시각 포함). 한쪽만 넣은 것은 공개(SLR-04)에서 막는다 */
	SALE_PERIOD_INVALID("PERFORMANCE001", HttpStatus.BAD_REQUEST, "판매 종료 일시는 판매 오픈 일시보다 늦어야 합니다."),

	// 취소 규정은 100번대를 쓴다. (공연 본체는 0xx)
	/** 없는 취소 규정이거나 다른 사업체의 규정. 서비스 기본 규정과 내 사업체 규정만 볼 수 있고, 남의 규정은 없는 것과 같은 404로 답한다 */
	CANCEL_POLICY_NOT_FOUND("PERFORMANCE100", HttpStatus.NOT_FOUND, "존재하지 않는 취소 규정입니다."),
	/**
	 * 취소 규정 구간끼리의 관계가 틀림(뒤집힌 구간, 당일부터 시작하지 않음, 제한 없음 구간이 없거나 마지막이 아님, 빈 날짜·겹치는 날짜).
	 * 프론트가 고칠 곳은 같은 구간 목록이라 코드는 하나로 두고, 무엇이 틀렸는지는 메시지로 알려 준다
	 */
	CANCEL_POLICY_RULES_INVALID("PERFORMANCE101", HttpStatus.BAD_REQUEST, "취소 규정 구간이 올바르지 않습니다."),
	/** 서비스 기본 규정을 수정하려 함. 기본 규정은 모든 사업체가 예시로 보고 고르는 규정이라 읽기 전용이다 */
	DEFAULT_CANCEL_POLICY_NOT_EDITABLE("PERFORMANCE102", HttpStatus.CONFLICT, "서비스 기본 규정은 수정할 수 없습니다."),
	/**
	 * 공개(PUBLISHED)되었거나 취소(CANCELED)된 공연이 쓰는 규정을 수정하려 함.
	 * 판매자가 그 공연에 적용된 기준을 볼 수 있는 곳이 공연의 규정뿐이라 공개 후에는 내용이 바뀌면 안 된다. 바꾸려면 새 규정을 만든다
	 */
	CANCEL_POLICY_IN_USE("PERFORMANCE103", HttpStatus.CONFLICT, "공개되었거나 취소된 공연이 쓰는 취소 규정은 수정할 수 없습니다. 새 규정을 만들어 주세요."),
	/**
	 * 같은 사업체에 대소문자만 다른 것까지 같은 이름의 규정이 있거나, 서비스 기본 규정과 같은 이름.
	 * 공연 등록 때 규정을 이름으로 고르는데 같은 이름이 쌓이면 구분할 수 없어서 막는다
	 */
	CANCEL_POLICY_NAME_DUPLICATED("PERFORMANCE104", HttpStatus.CONFLICT, "같은 이름의 취소 규정이 이미 있습니다."),
	/**
	 * 예매자가 볼 수 없는 회차. 없는 회차, 공개되지 않은 공연의 회차, 취소된 회차를 구분하지 않고 같은 404로 답한다.
	 * 판매자 쪽 회차 기능과 헷갈리지 않게 역할 이름(User)을 붙인다
	 */
	USER_SCHEDULE_NOT_FOUND("PERFORMANCE200", HttpStatus.NOT_FOUND, "존재하지 않는 회차입니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
