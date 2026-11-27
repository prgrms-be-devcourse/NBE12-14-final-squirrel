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
	 * 취소 이력의 회차 좌석에는 등급 이름이 없어서 이름을 고치면 이력이 다르게 보인다. 등급·구역 설정과 공연 수정이 함께 쓴다.
	 * 회차 추가(SLR-12)·수정(SLR-13), 판매 좌석 생성(SLR-14), 판매 일시 중지·재개(SLR-26·27)도 취소된 공연을 막을 때 쓴다
	 */
	PERFORMANCE_CANCELED("PERFORMANCE002", HttpStatus.CONFLICT, "취소된 공연은 수정할 수 없습니다."),
	/**
	 * 공개(PUBLISHED)된 공연의 공연장을 다른 공연장으로 바꾸려 함. 같은 공연장 번호를 그대로 보내는 것은 막지 않는다.
	 * 수정이 전체 교체라 공연장 번호도 매번 함께 오기 때문이다. 공개 후에는 구역 설정과 판매 좌석이 이미 정해져 있다
	 */
	VENUE_CHANGE_NOT_ALLOWED("PERFORMANCE003", HttpStatus.CONFLICT, "공개된 공연은 공연장을 바꿀 수 없습니다."),
	/** 공개(PUBLISHED)된 공연의 취소 규정을 다른 규정으로 바꾸려 함. 같은 규정 번호를 그대로 보내는 것은 막지 않는다. 프론트가 되돌릴 칸을 알도록 공연장과 코드를 나눈다 */
	CANCEL_POLICY_CHANGE_NOT_ALLOWED("PERFORMANCE004", HttpStatus.CONFLICT, "공개된 공연은 취소 규정을 바꿀 수 없습니다."),
	/** 판매 오픈 일시가 지난(판매가 열린) 공연의 오픈 일시를 바꾸려 함. 열린 판매가 닫히거나 예매 기준이 흔들린다 */
	SALE_OPEN_CHANGE_NOT_ALLOWED("PERFORMANCE005", HttpStatus.CONFLICT, "판매가 열린 공연은 판매 오픈 일시를 바꿀 수 없습니다."),
	/**
	 * 좌석을 차지한 예매(결제 대기·결제 완료·일부 취소)가 있는 공연의 1인 최대 매수를 줄이려 함.
	 * 줄이면 이미 새 한도를 넘긴 사람이 생긴다. 전체 취소·만료된 예매는 좌석을 차지하지 않아 세지 않는다
	 */
	MAX_PER_PERSON_REDUCE_NOT_ALLOWED("PERFORMANCE006", HttpStatus.CONFLICT, "예매가 있는 공연은 1인 최대 매수를 줄일 수 없습니다."),
	/**
	 * 공연장 변경·러닝타임 늘리기·회차 추가와 수정으로 같은 공연장의 다른 회차와 시간이 겹침. 회차가 차지하는 시간은 시작부터 러닝타임 + 1시간이다.
	 * 다른 판매자의 회차는 정보를 알려 주지 않는다. 공연 수정(SLR-03)은 겹치는 이 공연의 회차 일시를,
	 * 회차 추가(SLR-12)와 수정(SLR-13)은 겹치는 내 사업체 회차의 공연명·일시·사용 종료 시각을 메시지에 넣는다
	 */
	SCHEDULE_TIME_OVERLAP("PERFORMANCE007", HttpStatus.CONFLICT, "같은 공연장의 다른 회차와 시간이 겹칩니다."),
	/**
	 * 판매자 화면에서 없는 회차이거나 다른 사업체의 회차. 번호가 존재하는지 알려주지 않으려고 둘 다 같은 404로 답한다.
	 * 예매자용 PERFORMANCE200 과 섞이지 않게 따로 두었고, 판매자 회차 API(회차 추가·수정 등)도 쓸 수 있다
	 */
	SELLER_SCHEDULE_NOT_FOUND("PERFORMANCE008", HttpStatus.NOT_FOUND, "존재하지 않는 회차입니다."),
	/**
	 * 작성 중(DRAFT) 공연에 지금부터 3시간 이내(정확히 3시간 뒤 포함)에 시작하는 회차를 추가하려 함.
	 * 공개는 판매 오픈까지 3시간보다 더 남아야 하고 회차는 오픈보다 늦어야 해서, 이런 회차가 있으면 그 공연을 공개할 수 없다.
	 * 작성 중 공연의 회차 일시를 그렇게 바꾸려 할 때(SLR-13)도 쓴다
	 */
	DRAFT_SCHEDULE_TOO_SOON("PERFORMANCE009", HttpStatus.BAD_REQUEST, "작성 중인 공연에는 지금부터 3시간 뒤의 회차부터 추가할 수 있습니다."),
	/**
	 * 공개된 공연의 취소되지 않은 회차가 모두 시작한 뒤 회차를 추가하려 함. 끝난 공연에 회차를 덧붙이면 1인 최대 매수와 잠긴 가격이
	 * 그대로 이어지고 판매 기간도 늘릴 수 없어서, 새 공연을 등록하게 한다
	 */
	ALL_SCHEDULES_STARTED("PERFORMANCE010", HttpStatus.CONFLICT, "회차가 모두 시작된 공연에는 회차를 추가할 수 없습니다. 새 공연을 등록해 주세요."),
	/**
	 * 만료(EXPIRED)가 아닌 예매가 있는 회차의 일시를 바꾸려 함. 전체 취소도 막는다. 예매자의 내역에는 지금 일시가 보이고
	 * 바꾸기 전 일시가 남지 않기 때문이다
	 */
	SCHEDULE_HAS_BOOKING("PERFORMANCE011", HttpStatus.CONFLICT, "결제 대기 중이거나 결제된 적 있는(취소 포함) 예매가 있어 일시를 바꿀 수 없습니다."),
	/** 연기(POSTPONED)된 회차의 일시를 바꾸려 함. 새 일정은 연기 이력·상태와 함께 바뀌어야 해서 연기 기능이 맡는다 */
	SCHEDULE_POSTPONED("PERFORMANCE012", HttpStatus.CONFLICT, "연기된 회차는 일시를 바꿀 수 없습니다."),
	// 013·014 는 머지하지 않고 닫은 회차·공연 취소 브랜치가 쓰던 번호라 비워 둔다.
	/** 작성 중(DRAFT) 공연의 회차 판매를 일시 중지·재개(SLR-26·27)하려 함. 아직 팔고 있지 않아 멈출 것이 없다 */
	SCHEDULE_SALE_DRAFT_PERFORMANCE("PERFORMANCE015", HttpStatus.CONFLICT, "작성 중인 공연의 회차는 중지하거나 재개할 수 없습니다."),
	/** 고른 회차(또는 모두) 중에 일시 중지할 수 있는 회차(판매 중이고 시작 전)가 하나도 없음 */
	NO_SCHEDULE_TO_PAUSE("PERFORMANCE016", HttpStatus.CONFLICT, "중지할 수 있는 회차가 없습니다."),
	/** 고른 회차(또는 모두) 중에 재개할 수 있는 회차(일시 중지이고 시작 전)가 하나도 없음 */
	NO_SCHEDULE_TO_RESUME("PERFORMANCE017", HttpStatus.CONFLICT, "재개할 수 있는 회차가 없습니다."),
	/** 시작 전 회차가 모두 일시 중지인 공개 공연에 회차를 추가하려 함 */
	SCHEDULE_ADD_WHILE_PAUSED("PERFORMANCE018", HttpStatus.CONFLICT, "판매를 일시 중지한 공연에는 회차를 추가할 수 없습니다. 먼저 판매를 재개해 주세요."),

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
	/**
	 * 아직 설정하지 않은(UNSET) 구역이 남음. 구역 이름은 메시지로 알려 준다. 공개 후에는 고칠 수 없어서 판매 여부를 모두 정하게 한다.
	 * 판매 좌석을 만들 때(SLR-14, 회차 추가의 즉시 생성)도 같은 조건이다
	 */
	ZONE_UNSET_REMAINS("PERFORMANCE403", HttpStatus.BAD_REQUEST, "설정하지 않은 구역이 있습니다."),
	/**
	 * 판매할(ASSIGNED) 구역이 하나도 없음. 모든 구역이 의도적 미판매면 살 수 있는 좌석이 0개인 공연이 공개된다.
	 * 판매 좌석을 만들 때(SLR-14, 회차 추가의 즉시 생성)도 같은 조건이다
	 */
	NO_ASSIGNED_ZONE("PERFORMANCE404", HttpStatus.BAD_REQUEST, "판매할 구역이 하나 이상 있어야 공개할 수 있습니다."),
	/**
	 * 판매 종료 일시가 지났거나 지금과 같음. 팔 수 없는 공연이 공개되면 되돌릴 API가 없고 판매자가 원인을 모른다.
	 * 공개된 공연에 회차를 추가할 때(SLR-12)도 판매가 끝났으면 막는다. 그 회차는 예매할 수 없다
	 */
	SALE_CLOSED("PERFORMANCE405", HttpStatus.BAD_REQUEST, "판매 종료 일시가 지나 공개할 수 없습니다."),
	/**
	 * 판매 오픈까지 3시간이 남지 않음(이미 지난 경우 포함). 판매 좌석은 오픈 24시간 전부터 스케줄러가 주기적으로 만들어서,
	 * 오픈 직전에 공개하면 판매가 열렸는데 좌석이 없는 틈이 생긴다. 3시간은 임시값이고 스케줄러 주기가 바뀌면 다시 정한다
	 */
	SALE_OPEN_TOO_SOON("PERFORMANCE406", HttpStatus.BAD_REQUEST, "판매 오픈 3시간 전까지만 공개할 수 있습니다."),
	/**
	 * 판매 오픈보다 먼저(같은 시각 포함) 시작하는 회차가 있음. 그 회차는 한 장도 팔 수 없고 보통 날짜 입력 실수다. 회차 일시는 메시지로 알려 준다.
	 * 회차 추가(SLR-12)와 수정(SLR-13)은 판매 오픈이 설정되어 있으면 작성 중 공연에서도 막고, 메시지에 판매 오픈 일시를 넣는다
	 */
	SCHEDULE_BEFORE_SALE_OPEN("PERFORMANCE407", HttpStatus.BAD_REQUEST, "판매 오픈보다 먼저 시작하는 회차가 있습니다."),
	/**
	 * 판매 종료가 마지막 회차 시작보다 늦음. 기능상 문제는 없지만 예매자 공연 상세에 공연이 끝난 뒤까지 판매 기간으로 보인다.
	 * 공개된 공연의 회차 일시를 앞당겨 이렇게 될 때(SLR-13)도 막고, 메시지에 판매 종료를 앞당길 기준 일시를 넣는다
	 */
	SALE_CLOSE_AFTER_LAST_SCHEDULE("PERFORMANCE408", HttpStatus.BAD_REQUEST, "판매 종료 일시는 마지막 회차 시작보다 늦을 수 없습니다."),

	// 판매 좌석 생성은 500번대를 쓴다.
	/**
	 * 이미 판매 좌석이 만들어진 회차에서 다시 만들려 함. 버튼을 두 번 누르거나 스케줄러·다른 요청과 겹쳐도 한 번만 만들어지게 하는 마지막 방어선이다
	 * (락 안에서 다시 읽어 본 값이거나 seats_generated_at 조건부 UPDATE 가 0행일 때)
	 */
	SEATS_ALREADY_GENERATED("PERFORMANCE500", HttpStatus.CONFLICT, "이미 판매 좌석이 생성된 회차입니다."),
	/** 취소된 회차에서 판매 좌석을 만들려 함. 팔 수 없는 좌석만 쌓이고 가격이 잠긴다. 회차 수정·취소(SLR-13·15)도 취소된 회차를 막을 때 쓸 수 있다 */
	SCHEDULE_CANCELED("PERFORMANCE501", HttpStatus.CONFLICT, "취소된 회차는 판매 좌석을 만들 수 없습니다."),
	/**
	 * 이미 시작한(시작 시각이 지금 이전이거나 같은) 회차에서 판매 좌석을 만들려 함. 팔 수 없는 좌석만 쌓이고 가격이 잠긴다.
	 * 시작한 회차의 일시를 바꾸려 할 때(SLR-13)도 메시지를 바꿔 쓴다. 지난 공연이 다시 팔리면 안 된다
	 */
	SCHEDULE_ALREADY_STARTED("PERFORMANCE502", HttpStatus.BAD_REQUEST, "이미 시작한 회차는 판매 좌석을 만들 수 없습니다."),
	/**
	 * 복사할 공연장 좌석이 하나도 없어 좌석이 0행으로 만들어짐. 판매 구역이 있는데 그 구역에 공연장 좌석이 없는 깨진 데이터라
	 * 지금은 일어나지 않는다. 그대로 두면 빈 회차가 만들어진 것으로 기록되어 등급·가격·구역이 잠기므로 롤백하고 거절한다
	 */
	NO_VENUE_SEATS_TO_COPY("PERFORMANCE503", HttpStatus.CONFLICT, "복사할 공연장 좌석이 없어 판매 좌석을 만들 수 없습니다. 공연장 정보를 확인해 주세요.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
