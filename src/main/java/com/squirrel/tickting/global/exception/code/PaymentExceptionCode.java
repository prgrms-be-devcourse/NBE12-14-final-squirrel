package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 결제 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum PaymentExceptionCode implements ExceptionCode {

	/** 공연이 받지 않는 수령 방법. 현장 수령만 되는 공연에 배송을 고른 경우 등 */
	RECEIVE_TYPE_NOT_ALLOWED("PAYMENT000", HttpStatus.BAD_REQUEST, "이 공연에서 선택할 수 없는 수령 방법입니다."),
	/** 배송을 골랐는데 받는 사람 이름·연락처·주소 중 비어 있는 것이 있음 */
	RECIPIENT_REQUIRED("PAYMENT001", HttpStatus.BAD_REQUEST, "배송 받을 사람의 이름, 연락처, 주소를 모두 입력해 주세요."),
	/** 없는 주문 ID 이거나 남의 결제. 번호가 존재하는지 알려주지 않으려고 같은 404 로 답한다 */
	PAYMENT_NOT_FOUND("PAYMENT002", HttpStatus.NOT_FOUND, "존재하지 않는 결제입니다."),
	/** 결제 준비(READY) 상태가 아님. 이미 승인했거나 다른 승인 요청이 처리 중이거나 실패한 결제 */
	PAYMENT_ALREADY_PROCESSED("PAYMENT003", HttpStatus.CONFLICT, "이미 처리 중이거나 끝난 결제입니다."),
	/** 요청한 금액이 서버가 계산한 금액과 다름. 토스를 부르지 않고 거절한다 */
	AMOUNT_MISMATCH("PAYMENT004", HttpStatus.BAD_REQUEST, "결제 금액이 주문 금액과 다릅니다."),
	/** 토스가 승인을 거절함. 사유(카드사 거절, 한도 초과 등)는 토스가 준 문구를 그대로 쓴다 */
	PAYMENT_REJECTED("PAYMENT005", HttpStatus.BAD_REQUEST, "결제가 승인되지 않았습니다."),
	/** 토스 통신 오류·시간 초과 등으로 승인 여부를 알 수 없음. 결제는 승인 중으로 남고 스케줄러가 토스에 조회해서 확정한다 */
	PAYMENT_RESULT_UNKNOWN("PAYMENT006", HttpStatus.BAD_GATEWAY, "결제 결과를 확인하고 있습니다. 잠시 뒤 내 예매에서 확인해 주세요.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
