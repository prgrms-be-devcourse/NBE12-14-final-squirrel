package com.squirrel.tickting.domain.queue.facade;

import com.squirrel.tickting.domain.performance.service.ScheduleService;
import com.squirrel.tickting.domain.queue.dto.QueueResponse;
import com.squirrel.tickting.domain.queue.service.QueueProperties;
import com.squirrel.tickting.domain.queue.service.QueueService;
import com.squirrel.tickting.domain.user.service.UserMemberService;
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
	private final UserMemberService userMemberService;
	private final QueueProperties queueProperties;

	/**
	 * 이메일 인증을 안 한 예매자는 AUTH307, 판매 중이 아닌 회차는 BOOKING002, 없는 회차는 BOOKING004 로 거절한다. (예매와 같은 기준)
	 * 인증 확인이 회차 확인보다 먼저다. 줄을 서기 전에 막아야, 줄을 다 선 뒤 막혀 기다린 시간을 잃거나 인증하는 동안 입장 시간이 줄지 않는다.
	 * 인증 여부는 DB 에서 읽는다. 입장(POST)은 사람마다 예매하기를 누를 때 한 번이고, 대기 중 상태 확인(GET)에는 붙지 않는다.
	 * 대기열을 끈 상태(enabled=false, 테스트·로컬 확인용)에서는 이 확인도 함께 빠진다.
	 */
	public QueueResponse enter(Long userId, Long scheduleId) {
		if (queueProperties.enabled()) {
			userMemberService.requireEmailVerified(userId);
		}
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
