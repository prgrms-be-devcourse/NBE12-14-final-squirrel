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
	/**
	 * 취소(CANCELED)된 공연을 고치려 함. 공연 취소는 모든 회차를 취소해 locked 가 false 가 되므로 따로 막는다.
	 * 취소 이력의 회차 좌석에는 등급 이름이 없어서 이름을 고치면 이력이 다르게 보인다. 등급·구역 설정과 공연 수정이 함께 쓴다
	 */
	PERFORMANCE_CANCELED("PERFORMANCE002", HttpStatus.CONFLICT, "취소된 공연은 수정할 수 없습니다."),

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
	USER_SCHEDULE_NOT_FOUND("PERFORMANCE200", HttpStatus.NOT_FOUND, "존재하지 않는 회차입니다."),
	/** 예매자가 고른 구역이 그 회차의 공연장 구역이 아님(없는 구역 포함). 다른 공연장의 구역 번호를 넣어 좌석을 보는 것을 막는다 */
	USER_ZONE_NOT_FOUND("PERFORMANCE201", HttpStatus.NOT_FOUND, "존재하지 않는 구역입니다."),
	/**
	 * 판매 좌석을 아직 만들지 않은 회차(판매 오픈 24시간 전보다 이전)의 좌석을 보려 함. 고를 수 있는 좌석이 없어서 404로 답한다.
	 * 구역 요약은 같은 회차에서 잔여석 0으로 보여 준다. 화면이 "아직 좌석을 고를 수 없음"을 따로 보여 줄 수 있게 회차 없음과 코드를 나눈다
	 */
	USER_SEATS_NOT_GENERATED("PERFORMANCE202", HttpStatus.NOT_FOUND, "아직 좌석을 선택할 수 없는 회차입니다."),

	// 등급·구역 설정은 300번대를 쓴다.
	/** 없는 등급이거나 이 공연의 등급이 아님. 같은 사업체의 다른 공연 등급도 경로의 공연과 맞지 않으면 같은 404 로 답한다 */
	GRADE_NOT_FOUND("PERFORMANCE300", HttpStatus.NOT_FOUND, "존재하지 않는 등급입니다."),
	/**
	 * 취소되지 않은 회차 중 하나라도 판매 좌석을 만든 공연(locked)의 등급·가격·구역 설정을 고치려 함.
	 * 가격은 좌석을 만들 때 복사되므로 고치면 회차끼리, 등급과 좌석끼리 가격이 어긋난다
	 */
	GRADE_LOCKED("PERFORMANCE301", HttpStatus.CONFLICT, "판매 좌석이 생성되어 등급·가격·구역 설정을 수정할 수 없습니다."),
	/** 같은 공연에 같은 등급이 이미 있음. 같은 등급이 둘이면 예매자 가격표에 같은 이름이 두 줄 나와 구분할 수 없다 */
	GRADE_NAME_DUPLICATED("PERFORMANCE302", HttpStatus.CONFLICT, "같은 등급이 이미 있습니다."),
	/**
	 * 전석(ALL)과 다른 등급이 함께 구역에 지정되려 함. 함께 보이면 "전석"이 틀린 말이 된다.
	 * 구역에 지정되지 않은 등급은 예매자 화면에 보이지 않아서 만들어 두는 것은 막지 않는다
	 */
	GRADE_ALL_MIXED("PERFORMANCE303", HttpStatus.CONFLICT, "전석은 다른 등급과 함께 구역에 지정할 수 없습니다."),
	/** 구역 설정에 이 공연의 공연장에 없는 구역(다른 공연장의 구역이나 없는 구역 번호)을 보냄 */
	ZONE_NOT_FOUND_IN_VENUE("PERFORMANCE304", HttpStatus.BAD_REQUEST, "이 공연의 공연장에 없는 구역입니다."),
	/**
	 * 구역에 이 공연의 등급이 아닌 등급을 지정하려 함. 없는 등급 번호와 다른 공연(남의 공연 포함)의 등급을
	 * 같은 코드·메시지로 답해서 다른 사업체의 등급 번호가 있는지 알려주지 않는다
	 */
	GRADE_NOT_IN_PERFORMANCE("PERFORMANCE305", HttpStatus.BAD_REQUEST, "이 공연의 등급이 아닙니다."),
	/** 구역 상태와 등급 지정이 맞지 않음. ASSIGNED 인데 등급이 없거나, UNSET·INTENTIONALLY_UNSOLD 인데 등급이 있음 */
	ZONE_ASSIGNMENT_INVALID("PERFORMANCE306", HttpStatus.BAD_REQUEST, "구역 상태와 등급 지정이 맞지 않습니다."),

	// 공연 공개는 400번대를 쓴다.
	/** 작성 중(DRAFT)이 아닌 공연을 공개하려 함. 이미 공개·취소된 공연이거나, 공개 두 번·공연 취소와 겹쳐 조건부 UPDATE 가 0행일 때도 쓴다 */
	PERFORMANCE_NOT_DRAFT("PERFORMANCE400", HttpStatus.CONFLICT, "작성 중인 공연만 공개할 수 있습니다."),
	/** 판매 오픈·종료 일시, 1인 최대 매수, 수령 방법, 예매 수수료 중 비어 있는 것이 있음. 작성 중에는 비워 둘 수 있지만 공개 때는 모두 있어야 한다 */
	SALE_FIELDS_MISSING("PERFORMANCE401", HttpStatus.BAD_REQUEST, "판매 오픈·종료 일시, 1인 최대 매수, 수령 방법, 예매 수수료를 모두 설정해야 공개할 수 있습니다."),
	/** 취소되지 않은 회차가 하나도 없음. 회차가 없으면 예매자가 고를 수 있는 것이 없다 */
	NO_ACTIVE_SCHEDULE("PERFORMANCE402", HttpStatus.BAD_REQUEST, "취소되지 않은 회차가 하나 이상 있어야 공개할 수 있습니다."),
	/** 아직 설정하지 않은(UNSET) 구역이 남음. 구역 이름은 메시지로 알려 준다. 공개 후에는 고칠 수 없어서 판매 여부를 모두 정하게 한다 */
	ZONE_UNSET_REMAINS("PERFORMANCE403", HttpStatus.BAD_REQUEST, "설정하지 않은 구역이 있습니다."),
	/** 판매할(ASSIGNED) 구역이 하나도 없음. 모든 구역이 의도적 미판매면 살 수 있는 좌석이 0개인 공연이 공개된다 */
	NO_ASSIGNED_ZONE("PERFORMANCE404", HttpStatus.BAD_REQUEST, "판매할 구역이 하나 이상 있어야 공개할 수 있습니다."),
	/** 판매 종료 일시가 지났거나 지금과 같음. 팔 수 없는 공연이 공개되면 되돌릴 API가 없고 판매자가 원인을 모른다 */
	SALE_CLOSED("PERFORMANCE405", HttpStatus.BAD_REQUEST, "판매 종료 일시가 지나 공개할 수 없습니다."),
	/**
	 * 판매 오픈까지 3시간이 남지 않음(이미 지난 경우 포함). 판매 좌석은 오픈 24시간 전부터 스케줄러가 주기적으로 만들어서,
	 * 오픈 직전에 공개하면 판매가 열렸는데 좌석이 없는 틈이 생긴다. 3시간은 임시값이고 스케줄러 주기가 바뀌면 다시 정한다
	 */
	SALE_OPEN_TOO_SOON("PERFORMANCE406", HttpStatus.BAD_REQUEST, "판매 오픈 3시간 전까지만 공개할 수 있습니다."),
	/** 판매 오픈보다 먼저(같은 시각 포함) 시작하는 회차가 있음. 그 회차는 한 장도 팔 수 없고 보통 날짜 입력 실수다. 회차 일시는 메시지로 알려 준다 */
	SCHEDULE_BEFORE_SALE_OPEN("PERFORMANCE407", HttpStatus.BAD_REQUEST, "판매 오픈보다 먼저 시작하는 회차가 있습니다."),
	/** 판매 종료가 마지막 회차 시작보다 늦음. 기능상 문제는 없지만 예매자 공연 상세에 공연이 끝난 뒤까지 판매 기간으로 보인다 */
	SALE_CLOSE_AFTER_LAST_SCHEDULE("PERFORMANCE408", HttpStatus.BAD_REQUEST, "판매 종료 일시는 마지막 회차 시작보다 늦을 수 없습니다.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
