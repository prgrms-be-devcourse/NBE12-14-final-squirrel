package com.squirrel.tickting.global.exception.code;

import com.squirrel.tickting.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 판매자 계정·사업체 관련 에러 코드. */
@Getter
@RequiredArgsConstructor
public enum SellerExceptionCode implements ExceptionCode {

	/** 이미 가입된 판매자 이메일 */
	EMAIL_DUPLICATED("SELLER000", HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
	/** 이미 등록된 사업자등록번호 (같은 사업자의 중복 가입 방지) */
	BUSINESS_NUMBER_DUPLICATED("SELLER001", HttpStatus.CONFLICT, "이미 등록된 사업자등록번호입니다."),

	/** 관리자 승인 전 사업체 */
	BUSINESS_PENDING("SELLER003", HttpStatus.FORBIDDEN,
		"승인 대기 중인 사업체입니다. 관리자 승인 후 로그인할 수 있습니다."),
	/** 반려된 사업체. 같은 사업자번호로 다시 신청할 수 없어서 재신청 안내 대신 문의를 안내한다 */
	BUSINESS_REJECTED("SELLER004", HttpStatus.FORBIDDEN,
		"가입 신청이 반려되었습니다. 이의가 있으시면 문의해 주세요."),
	/** 이용이 정지된 사업체 */
	BUSINESS_SUSPENDED("SELLER005", HttpStatus.FORBIDDEN, "이용이 정지된 사업체입니다."),

	/** 관리자가 조회·처리하려는 사업체가 없음 */
	BUSINESS_NOT_FOUND("SELLER006", HttpStatus.NOT_FOUND, "존재하지 않는 사업체입니다."),
	/** 승인·반려는 승인 대기(PENDING) 사업체에만 할 수 있다. 이미 처리된 사업체에 다시 요청함 */
	BUSINESS_ALREADY_REVIEWED("SELLER007", HttpStatus.CONFLICT, "이미 처리된 사업체입니다."),

	/** 담당자 초대 링크가 없거나, 만료됐거나, 이미 썼거나, 같은 이메일로 다시 초대해서 무효가 됨 */
	INVITATION_INVALID("SELLER008", HttpStatus.BAD_REQUEST, "만료되었거나 이미 사용한 초대 링크입니다. 대표에게 다시 초대를 요청해 주세요."),

	/** 대표가 관리하려는 계정이 없거나 다른 회사 계정 (번호가 있는지 알려주지 않으려고 같은 404) */
	MEMBER_NOT_FOUND("SELLER009", HttpStatus.NOT_FOUND, "존재하지 않는 담당자입니다."),
	/** 대표 계정을 비활성화하려 함 */
	OWNER_NOT_DEACTIVATABLE("SELLER010", HttpStatus.BAD_REQUEST, "대표 계정은 비활성화할 수 없습니다."),
	/** 이미 비활성인 담당자를 비활성화하려 함 */
	MEMBER_ALREADY_INACTIVE("SELLER011", HttpStatus.CONFLICT, "이미 비활성화된 담당자입니다."),
	/** 이미 활성인 담당자를 복구하려 함 */
	MEMBER_ALREADY_ACTIVE("SELLER012", HttpStatus.CONFLICT, "이미 사용 중인 담당자입니다."),
	/** 복구하려는 담당자의 이메일이 다른 회사에서 아직 사용 중 (활성 계정은 이메일당 하나) */
	EMAIL_ACTIVE_IN_OTHER_BUSINESS("SELLER013", HttpStatus.CONFLICT,
		"다른 회사에서 사용 중인 계정이라 복구할 수 없습니다. 이전 회사에 계정 비활성화를 요청해 주세요.");

	private final String code;
	private final HttpStatus status;
	private final String message;
}
