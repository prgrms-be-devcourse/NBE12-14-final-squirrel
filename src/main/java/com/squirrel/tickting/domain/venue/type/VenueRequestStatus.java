package com.squirrel.tickting.domain.venue.type;

/** 공연장 신청 처리 상태. */
public enum VenueRequestStatus {

	/** 접수됨 */
	RECEIVED,
	/** 처리 중 (관리자가 연락해서 좌석 배치도를 받는 중) */
	IN_PROGRESS,
	/** 등록 완료 */
	DONE,
	/** 등록 불가 (중복이거나 연락이 안 됨) */
	REJECTED
}
