package com.squirrel.tickting.domain.inquiry.type;

/** 문의 이력에 남기는 행동. */
public enum InquiryAction {

	/** 작성 */
	CREATED,
	/** 답변 전 수정 */
	UPDATED,
	/** 답변 전 삭제 */
	DELETED,
	/** 관리자 답변 */
	ANSWERED
}
