package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.domain.audit.type.ActorType;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceRequest;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.service.CancelPolicyService;
import com.squirrel.tickting.domain.performance.service.GradeService;
import com.squirrel.tickting.domain.performance.service.PerformanceLockNames;
import com.squirrel.tickting.domain.performance.service.PerformanceProperties;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.venue.service.VenueService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자의 공연 등록·수정·취소와 회차 수정·취소. 공연장(venue)·취소 규정(cancel policy)·공연(performance)·구역 설정(grade)·판매 좌석·예매·감사 로그 서비스를 엮고,
 * 쓰기는 TransactionTemplate 으로 한 트랜잭션에 묶는다. (조회는 공연의 읽기 전용 연관으로 서비스가 직접 응답을 만든다)
 */
@Component
@RequiredArgsConstructor
public class SellerPerformanceFacade {

	private final SellerPerformanceService sellerPerformanceService;
	private final VenueService venueService;
	private final CancelPolicyService cancelPolicyService;
	private final GradeService gradeService;
	private final ScheduleSeatService scheduleSeatService;
	private final BookingService bookingService;
	private final AuditLogService auditLogService;
	private final DistributedLockExecutor lockExecutor;
	private final PerformanceProperties properties;
	private final TransactionTemplate transactionTemplate;

	/**
	 * 공연을 작성 중(DRAFT)으로 등록하고 공연장의 모든 구역을 UNSET 으로 만든다.
	 * 확인(공연장, 취소 규정)은 읽기만 해서 트랜잭션 밖에서 먼저 하고, 쓰기만 한 트랜잭션으로 묶는다.
	 * 구역 설정 없이 공연만 남으면 공개 검사가 빈 구역을 "UNSET 없음"으로 잘못 읽으므로 둘은 함께 성공하거나 함께 취소된다.
	 * 잠금은 쓰지 않는다. 새 공연은 DRAFT 라 취소 규정 수정과 부딪히지 않고, 공연장 구역은 등록 중에 바뀌지 않는다.
	 */
	public SellerPerformanceResponse.Created register(Long businessId, Long createdBy,
		SellerPerformanceRequest.Save request) {
		List<Long> zoneIds = venueService.getActiveZoneIds(request.venueId());
		CancelPolicy cancelPolicy = cancelPolicyService.getVisible(request.cancelPolicyId(), businessId);

		Performance performance = transactionTemplate.execute(status -> {
			Performance created = sellerPerformanceService.create(businessId, createdBy, request.venueId(), cancelPolicy,
				request);
			gradeService.initializeZones(created, zoneIds);
			return created;
		});
		return SellerPerformanceResponse.Created.from(performance);
	}

	/**
	 * 공연을 전체 교체로 수정한다. 락은 커밋 뒤에 풀려야 해서 락을 먼저 잡고 그 안에서 트랜잭션을 연다.
	 * 락은 공연 단위(performance:{번호})다. 같은 공연의 등급·구역 수정(SLR-10·11), 공개(SLR-04), 판매 좌석 생성과 겹치지 않게 한다.
	 * 특히 공연장 변경이 좌석 생성과 겹치면 옛 공연장 좌석이 새로 만들어질 수 있다.
	 * 같은 공연장의 시간 겹침은 락 없이 검사만 한다. 공연장은 판매자가 미리 정해 오는 것이라 서로 다른 공연이 같은 순간에
	 * 같은 공연장의 같은 시간을 잡을 일이 매우 드물고, 겹침 검사가 그다음 요청을 막는다. 동시에 들어오는 경우의 마지막 방어선은
	 * DB 제약 TODO(doc/seller-progress.md)로 남겼다.
	 * 작성 중 취소 규정 변경은 규정 락을 잡지 않는다. 작성 중 공연이 쓰는 규정은 잠기지 않아 규정 수정과 겹쳐도 규칙 위반이 아니고,
	 * 공개와는 공연 락으로 막힌다.
	 * 검사 순서: 요청 형식(DB 전) → 공연 조회 404(락 밖) → 락 → 재조회 → 공연 한 행 → 규정·공연장 한 건 조회 → 회차 → 예매 → 회차 겹침.
	 * 앞에서 막히면 뒤의 쿼리는 나가지 않게 처리량이 적은 것(한 행 → 한 건 → 작은 목록 → 겹침 쿼리)부터 두었고, 측정하지는 않았다.
	 */
	public SellerPerformanceResponse.Updated update(Long businessId, Long performanceId,
		SellerPerformanceRequest.Save request) {
		sellerPerformanceService.validate(request);

		// 남의 공연은 락을 잡기 전에 404 로 끝내서 남의 공연 상태를 드러내지 않는다.
		sellerPerformanceService.getMinePerformance(businessId, performanceId);

		Performance updated = lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)),
			properties.lockWait(), () -> transactionTemplate.execute(
				status -> updateInTransaction(businessId, performanceId, request)));
		return SellerPerformanceResponse.Updated.from(updated);
	}

	/** 락을 기다리는 사이 상태가 바뀌었을 수 있어 락 밖의 판단은 믿지 않고 다시 읽는다. */
	private Performance updateInTransaction(Long businessId, Long performanceId,
		SellerPerformanceRequest.Save request) {
		// 현재 시각은 한 번만 읽어 모든 비교에 같은 값을 쓴다.
		LocalDateTime now = LocalDateTime.now();
		Performance performance = sellerPerformanceService.getMinePerformance(businessId, performanceId);

		sellerPerformanceService.validateUpdatable(performance, request, request.venueId(), request.cancelPolicyId(), now);

		boolean venueChanged = !performance.getVenueId().equals(request.venueId());
		boolean cancelPolicyChanged = !performance.getCancelPolicy().getId().equals(request.cancelPolicyId());
		// 공개된 공연은 위에서 이미 막혔으므로 아래 두 확인은 작성 중 공연에서만 일어난다.
		CancelPolicy newCancelPolicy = cancelPolicyChanged
			? cancelPolicyService.getVisible(request.cancelPolicyId(), businessId) : null;
		List<Long> newZoneIds = venueChanged ? venueService.getActiveZoneIds(request.venueId()) : null;

		sellerPerformanceService.validateScheduleRules(performance, request);
		// 작성 중 공연은 예매가 있을 수 없어 묻지 않는다.
		boolean maxPerPersonReduced = performance.getStatus() == PerformanceStatus.PUBLISHED
			&& request.maxPerPerson() < performance.getMaxPerPerson();
		if (maxPerPersonReduced && bookingService.existsOccupying(performanceId)) {
			throw new BusinessException(PerformanceExceptionCode.MAX_PER_PERSON_REDUCE_NOT_ALLOWED);
		}
		boolean runningTimeIncreased = request.runningTime() > performance.getRunningTime();
		if (venueChanged || runningTimeIncreased) {
			Long targetVenueId = venueChanged ? request.venueId() : performance.getVenueId();
			sellerPerformanceService.validateNoOverlap(performanceId, targetVenueId, request.runningTime());
		}

		sellerPerformanceService.update(performance, request, venueChanged ? request.venueId() : null, newCancelPolicy);
		if (venueChanged) {
			// 옛 공연장 기준의 구역 설정과 판매 좌석은 의미가 없다. 좌석은 스케줄러가 새 공연장 기준으로 다시 만든다.
			gradeService.resetZones(performance, newZoneIds);
			scheduleSeatService.deleteAllOfPerformance(performanceId, now);
		}
		return performance;
	}

	/**
	 * 공개 공연을 취소한다. 감사 로그와 환불 대상 건수가 다른 도메인이라 파사드가 맡는다. 락은 공연 수정과 같은 공연 단위다.
	 * 환불은 회차 취소와 같게 예매 도메인의 스케줄러가 취소 회차를 보고 만든다. 좌석 점유는 공연 락을 잡지 않아 취소 순간의 점유는
	 * 막지 못하지만, 그 예매는 결제가 막혀 만료된다.
	 * 검사 순서: 요청 형식(DB 전) → 공연 조회 404(락 밖) → 락 → 재조회 → 공연 상태 → 회차 → 조건부 UPDATE → 감사 로그 → 건수.
	 */
	public SellerPerformanceResponse.Canceled cancel(Long businessId, Long sellerId, Long performanceId,
		SellerPerformanceRequest.Cancel request) {
		// 남의 공연은 락을 잡기 전에 404 로 끝내서 남의 공연 상태를 드러내지 않는다.
		sellerPerformanceService.getMinePerformance(businessId, performanceId);

		return lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(
				status -> cancelInTransaction(businessId, sellerId, performanceId, request.reason())));
	}

	/** 감사 로그 저장이 실패하면 취소도 함께 되돌린다. 누가 취소했는지 남지 않은 채 환불이 나가면 안 된다. */
	private SellerPerformanceResponse.Canceled cancelInTransaction(Long businessId, Long sellerId, Long performanceId,
		String reason) {
		LocalDateTime now = LocalDateTime.now();
		Performance performance = sellerPerformanceService.getMinePerformance(businessId, performanceId);
		// 일괄 UPDATE 는 엔티티에 반영되지 않아, 이 값은 취소하기 전 상태다.
		PerformanceStatus beforeStatus = performance.getStatus();
		List<Long> scheduleIds = sellerPerformanceService.cancel(performance, reason, now);
		auditLogService.saveHistory(AuditLogCommand.builder()
			.actorType(ActorType.SELLER)
			.actorId(sellerId)
			.businessId(businessId)
			.targetType("PERFORMANCE")
			.targetId(performanceId)
			.action("CANCEL")
			.before(Map.of("status", beforeStatus))
			.after(Map.of("status", PerformanceStatus.CANCELED, "reason", reason, "canceledScheduleIds", scheduleIds))
			.build());
		return SellerPerformanceResponse.Canceled.builder()
			.performanceId(performanceId)
			.status(PerformanceStatus.CANCELED)
			.canceledScheduleCount(scheduleIds.size())
			.refundRequestedCount(bookingService.countRefundTargets(scheduleIds))
			.build();
	}

	/**
	 * 회차의 공연 일시를 바꾼다. 예매 확인이 예매 도메인이라 파사드가 맡는다.
	 * 락은 공연 단위(performance:{번호})라 같은 공연의 회차 추가·공연 수정·공개·판매 좌석 생성과 겹치지 않는다.
	 * 좌석 점유는 이 락을 잡지 않아, 예매를 확인한 직후 저장하기 전에 들어온 점유는 막지 못한다. (알고 감수, doc/seller-progress.md TODO)
	 * 검사 순서: 회차 조회 404(락 밖) → 락 → 재조회 → 공연·회차 상태 → 같은 일시 → 새 일시 → (공개) 예매 → 판매 종료 → 겹침.
	 * 앞에서 막히면 뒤의 쿼리는 나가지 않는다. 처리량은 추정이고 측정하지 않았다.
	 */
	public SellerPerformanceResponse.ScheduleUpdated updateSchedule(Long businessId, Long scheduleId,
		SellerPerformanceRequest.ScheduleSave request) {
		// 남의 회차는 락을 잡기 전에 404 로 끝낸다. 락 이름에 쓸 공연 번호도 여기서 얻는다.
		Long performanceId = sellerPerformanceService.getMySchedule(businessId, scheduleId).getPerformance().getId();

		return lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(
				status -> updateScheduleInTransaction(businessId, scheduleId, request.startAt())));
	}

	/** 락을 기다리는 사이 상태가 바뀌었을 수 있어 회차를 다시 읽는다. */
	private SellerPerformanceResponse.ScheduleUpdated updateScheduleInTransaction(Long businessId, Long scheduleId,
		LocalDateTime startAt) {
		// 현재 시각은 한 번만 읽어 모든 비교·UPDATE·응답에 같은 값을 쓴다.
		LocalDateTime now = LocalDateTime.now();
		PerformanceSchedule schedule = sellerPerformanceService.getMySchedule(businessId, scheduleId);
		sellerPerformanceService.validateReschedulable(schedule, now);
		// 값이 바뀌지 않으면 예매가 있어도 막을 것이 없다.
		if (schedule.getStartAt().equals(startAt)) {
			return sellerPerformanceService.unchangedScheduleResponse(schedule);
		}
		Performance performance = schedule.getPerformance();
		sellerPerformanceService.validateRescheduleStartAt(performance, startAt, now);
		// 작성 중 공연은 예매가 있을 수 없어 묻지 않는다.
		if (performance.getStatus() == PerformanceStatus.PUBLISHED && bookingService.existsNotExpired(scheduleId)) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_HAS_BOOKING);
		}
		return sellerPerformanceService.reschedule(businessId, schedule, startAt, now);
	}

	/**
	 * 회차를 취소한다. 감사 로그와 환불 대상 건수가 다른 도메인이라 파사드가 맡는다. 락은 회차 수정과 같은 공연 단위(performance:{번호})다.
	 * 여기서는 환불을 만들지도 토스를 부르지도 않는다. 예매 도메인의 스케줄러가 취소 회차의 예매에 환불을 만들고 끝난 환불을 예매에 반영한다.
	 * 좌석 점유는 공연 락을 잡지 않아 취소 순간의 점유는 막지 못하지만, 그 예매는 결제가 막혀 만료된다.
	 * 검사 순서: 요청 형식(DB 전) → 회차 조회 404(락 밖) → 락 → 재조회 → 공연·회차 상태 → 조건부 UPDATE → 감사 로그 → 건수.
	 */
	public SellerPerformanceResponse.ScheduleCanceled cancelSchedule(Long businessId, Long sellerId, Long scheduleId,
		SellerPerformanceRequest.Cancel request) {
		// 남의 회차는 락을 잡기 전에 404 로 끝낸다. 락 이름에 쓸 공연 번호도 여기서 얻는다.
		Long performanceId = sellerPerformanceService.getMySchedule(businessId, scheduleId).getPerformance().getId();

		return lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(
				status -> cancelScheduleInTransaction(businessId, sellerId, scheduleId, request.reason())));
	}

	/** 감사 로그 저장이 실패하면 취소도 함께 되돌린다. 누가 취소했는지 남지 않은 채 환불이 나가면 안 된다. */
	private SellerPerformanceResponse.ScheduleCanceled cancelScheduleInTransaction(Long businessId, Long sellerId,
		Long scheduleId, String reason) {
		LocalDateTime now = LocalDateTime.now();
		PerformanceSchedule schedule = sellerPerformanceService.getMySchedule(businessId, scheduleId);
		// 일괄 UPDATE 는 엔티티에 반영되지 않아, 이 값은 취소하기 전 상태다.
		ScheduleStatus beforeStatus = schedule.getStatus();
		sellerPerformanceService.cancelSchedule(schedule, reason, now);
		auditLogService.saveHistory(AuditLogCommand.builder()
			.actorType(ActorType.SELLER)
			.actorId(sellerId)
			.businessId(businessId)
			.targetType("SCHEDULE")
			.targetId(scheduleId)
			.action("CANCEL")
			.before(Map.of("status", beforeStatus))
			.after(Map.of("status", ScheduleStatus.CANCELED, "reason", reason))
			.build());
		return SellerPerformanceResponse.ScheduleCanceled.builder()
			.scheduleId(scheduleId)
			.status(ScheduleStatus.CANCELED)
			.refundRequestedCount(bookingService.countRefundTargets(List.of(scheduleId)))
			.build();
	}
}
