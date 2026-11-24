package com.squirrel.tickting.domain.inquiry.type;

/** 문의 상태. 답변이 하나뿐이라 둘만 둔다. 답변 전에만 작성자가 수정·삭제할 수 있다. */
public enum InquiryStatus {

	/** 답변 대기 */
	WAITING,
	/** 답변 완료 */
	ANSWERED
}
