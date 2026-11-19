package com.squirrel.tickting.domain.queue.dto;

import com.squirrel.tickting.domain.queue.type.QueueState;
import java.time.LocalDateTime;

/**
 * 대기열 상태 응답.
 *
 * @param status       내 상태
 * @param position     내 순번(1부터). 대기 중(WAITING)일 때만 있다. 앞에 (순번 - 1)명이 있다
 * @param waitingCount 지금 기다리는 전체 인원. 대기 중일 때만 있다
 * @param expiresAt    입장 만료 시각. 입장(ADMITTED)했을 때만 있다
 */
public record QueueResponse(QueueState status, Integer position, Long waitingCount, LocalDateTime expiresAt) {

	public static QueueResponse none() {
		return new QueueResponse(QueueState.NONE, null, null, null);
	}

	public static QueueResponse waiting(int position, long waitingCount) {
		return new QueueResponse(QueueState.WAITING, position, waitingCount, null);
	}

	public static QueueResponse admitted(LocalDateTime expiresAt) {
		return new QueueResponse(QueueState.ADMITTED, null, null, expiresAt);
	}
}
