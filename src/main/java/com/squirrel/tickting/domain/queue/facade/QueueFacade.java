package com.squirrel.tickting.domain.queue.facade;

import com.squirrel.tickting.domain.performance.service.ScheduleService;
import com.squirrel.tickting.domain.queue.dto.QueueResponse;
import com.squirrel.tickting.domain.queue.service.QueueService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 대기열에 들어가기 전에 회차를 확인하는 흐름. (공연 도메인의 회차 확인 + 대기열)
 * 없는 회차 번호나 판매 중이 아닌 회차로 Redis 에 키가 쌓이지 않게, 들어갈 때 "지금 예매할 수 있는 회차"인지 본다.
 * 확인 결과는 짧게 기억해서, 오픈 순간 요청이 몰려도 요청마다 DB 를 읽지 않는다.
 */
@Component
@RequiredArgsConstructor
public class QueueFacade {

	private final ScheduleService scheduleService;
	private final QueueService queueService;

	/**
	 * 판매 중이 아닌 회차는 BOOKING002, 없는 회차는 BOOKING004 로 거절한다. (예매와 같은 기준)
	 * 이메일 인증은 가입 때 마치므로 여기서 확인하지 않는다. 오픈 순간 입장이 몰리는 곳이라 모든 입장에 DB 조회를 붙이지 않으려는 것이다.
	 * (탈퇴한 회원은 입장할 수 있어도 예매 단계의 회원 조회에서 막힌다)
	 */
	public QueueResponse enter(Long userId, Long scheduleId) {
		if (!queueService.isKnownOpen(scheduleId)) {
			scheduleService.getBookable(scheduleId, LocalDateTime.now());
			queueService.markOpen(scheduleId);
		}
		return queueService.enter(userId, scheduleId);
	}

	public QueueResponse status(Long userId, Long scheduleId) {
		return queueService.status(userId, scheduleId);
	}

	public void leave(Long userId, Long scheduleId) {
		queueService.leave(userId, scheduleId);
	}
}
