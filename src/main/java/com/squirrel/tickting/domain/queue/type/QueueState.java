package com.squirrel.tickting.domain.queue.type;

/** 대기열에서 내 상태. */
public enum QueueState {
	/** 대기열에도 입장 목록에도 없다. 입장 시간이 지났거나, 나갔거나, 오래 확인하지 않아 밀려났다. 다시 들어가야 한다 */
	NONE,
	/** 순서를 기다리는 중 */
	WAITING,
	/** 입장했다. 만료 시각까지 구역 요약·구역 좌석 조회와 좌석 점유를 할 수 있다 */
	ADMITTED
}
