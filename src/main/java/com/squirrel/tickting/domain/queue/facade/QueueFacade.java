package com.squirrel.tickting.domain.queue.facade;

import com.squirrel.tickting.domain.performance.service.ScheduleService;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.queue.dto.QueueResponse;
import com.squirrel.tickting.domain.queue.service.QueueService;
import com.squirrel.tickting.domain.queue.type.QueueState;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.BookingExceptionCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 대기열에 들어가기 전에 회차를 확인하는 흐름. (공연 도메인의 회차 확인 + 대기열)
 * 없는 회차 번호나 판매 중이 아닌 회차로 Redis 에 키가 쌓이지 않게, 들어갈 때 "지금 예매할 수 있는 회차"인지 본다.
 * 확인 결과는 짧게 기억해서, 오픈 순간 요청이 몰려도 요청마다 DB 를 읽지 않는다.
 *
 * <p>판매가 일시 중지된 회차는 줄을 유지하고 입장만 멈춘다. 중지·재개는 대기열을 직접 건드리지 않고 여기서 회차 상태를 읽어
 * 알아본다. (표시를 쓰고 지우는 방식은 지우기에 실패하면 줄이 계속 멈춘다) 그래서 반영까지 기억하는 시간만큼 늦을 수 있다.
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
	 * 중지·취소된 회차라도 이미 대기·입장한 사람의 재진입(새로고침)은 거절하지 않는다. 거절하면 순번을 지키기 어렵다.
	 */
	public QueueResponse enter(Long userId, Long scheduleId) {
		ScheduleStatus scheduleStatus = findScheduleStatus(scheduleId);
		boolean halted = isHalted(scheduleStatus);
		if (halted) {
			QueueResponse joined = queueService.findJoined(userId, scheduleId);
			if (joined.status() != QueueState.NONE) {
				return afterCancelCheck(userId, scheduleId, scheduleStatus, joined);
			}
		}
		// 중지로 기억하고 있으면 입장 가능 표시가 남아 있어도 DB 를 다시 확인한다. 중지 직후 새 사람이 들어오지 않게 한다.
		if (halted || !queueService.isKnownOpen(scheduleId)) {
			scheduleService.getBookable(scheduleId, LocalDateTime.now());
			queueService.markOpen(scheduleId);
		}
		return queueService.enter(userId, scheduleId);
	}

	public QueueResponse status(Long userId, Long scheduleId) {
		ScheduleStatus scheduleStatus = findScheduleStatus(scheduleId);
		QueueResponse response = queueService.status(userId, scheduleId, isHalted(scheduleStatus));
		return afterCancelCheck(userId, scheduleId, scheduleStatus, response);
	}

	public void leave(Long userId, Long scheduleId) {
		queueService.leave(userId, scheduleId);
	}

	/** 취소된 회차를 기다리는 사람은 줄에서 빼고 알린다. 그 밖에는 받은 상태 그대로다. paused 는 일시 중지일 때만 남긴다. */
	private QueueResponse afterCancelCheck(Long userId, Long scheduleId, ScheduleStatus scheduleStatus,
		QueueResponse response) {
		if (scheduleStatus != ScheduleStatus.CANCELED) {
			return response;
		}
		if (response.status() == QueueState.WAITING) {
			queueService.leave(userId, scheduleId);
			throw new BusinessException(BookingExceptionCode.SCHEDULE_NOT_ON_SALE, "취소된 회차입니다.");
		}
		return response.status() == QueueState.ADMITTED
			? QueueResponse.admitted(response.expiresAt(), false) : response;
	}

	/**
	 * 줄이 있는 회차의 상태. 기억해 둔 값이 없으면 DB 에서 읽어 잠깐 기억한다.
	 * 줄이 없는 회차(없는 번호, 아직 아무도 서지 않은 회차)는 null 이다. 지킬 순번이 없고 새로 서는 것은 입장 확인이 거절한다.
	 * 그래서 없는 번호나 판매 중이 아닌 회차를 호출해도 Redis 에 키가 쌓이지 않는다.
	 */
	private ScheduleStatus findScheduleStatus(Long scheduleId) {
		if (!queueService.hasQueue(scheduleId)) {
			return null;
		}
		String remembered = queueService.findKnownScheduleStatus(scheduleId).orElseGet(() -> {
			String read = scheduleService.findStatus(scheduleId).map(Enum::name).orElse("");
			queueService.rememberScheduleStatus(scheduleId, read);
			return read;
		});
		return remembered.isEmpty() ? null : ScheduleStatus.valueOf(remembered);
	}

	private static boolean isHalted(ScheduleStatus scheduleStatus) {
		return scheduleStatus == ScheduleStatus.PAUSED || scheduleStatus == ScheduleStatus.CANCELED;
	}
}
