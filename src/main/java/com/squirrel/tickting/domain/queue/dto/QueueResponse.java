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
 * @param paused       회차 판매가 일시 중지되어 새로 입장시키지 않는 중인지. 줄에 없으면(NONE) false 다
 */
public record QueueResponse(QueueState status, Integer position, Long waitingCount, LocalDateTime expiresAt,
	boolean paused) {

	public static QueueResponse none() {
		return new QueueResponse(QueueState.NONE, null, null, null, false);
	}

	public static QueueResponse waiting(int position, long waitingCount, boolean paused) {
		return new QueueResponse(QueueState.WAITING, position, waitingCount, null, paused);
	}

	public static QueueResponse admitted(LocalDateTime expiresAt, boolean paused) {
		return new QueueResponse(QueueState.ADMITTED, null, null, expiresAt, paused);
	}
}
