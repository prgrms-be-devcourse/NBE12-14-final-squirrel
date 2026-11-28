package com.squirrel.tickting.domain.admin.facade;

import com.squirrel.tickting.domain.admin.dto.AdminVenueRequest;
import com.squirrel.tickting.domain.admin.dto.AdminVenueResponse;
import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.domain.audit.type.ActorType;
import com.squirrel.tickting.domain.booking.infra.repository.BookingRepository.PerformanceBookingCount;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.performance.cache.UserPerformanceCache;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.service.PerformanceLockNames;
import com.squirrel.tickting.domain.performance.service.PerformanceProperties;
import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.service.VenueLockNames;
import com.squirrel.tickting.domain.venue.service.VenueService;
import com.squirrel.tickting.domain.venue.type.VenueDeactivationMode;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.exception.code.VenueExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자의 공연장 사용 중지·재개·영향 미리보기(ADM-11~13). 공연장(venue)·공연(performance)·예매·감사 로그를 엮는다.
 * 공연장 상태와 새 판매(공연 등록·공연장 변경·공개·회차 추가)가 엇갈리지 않게 공연장 락을, 전부 취소는 판매자의 취소·연기·중지와
 * 엇갈리지 않게 대상 공연의 락까지 함께 잡는다. 환불은 만들지 않는다. 취소 회차만 보고 처리하는 스케줄러(추후 구현)가 만든다.
 */
@Component
@RequiredArgsConstructor
public class AdminVenueFacade {

	private static final String CANCEL_REASON_PREFIX = "공연장 사용 중지: ";
	private static final String TARGET_VENUE = "VENUE";

	private final VenueService venueService;
	private final SellerPerformanceService sellerPerformanceService;
	private final BookingService bookingService;
	private final AuditLogService auditLogService;
	private final UserPerformanceCache userPerformanceCache;
	private final DistributedLockExecutor lockExecutor;
	private final PerformanceProperties properties;
	private final TransactionTemplate transactionTemplate;

	/** 지금 전부 취소하면 무엇이 취소되는지 보여 준다. 읽기만 한다. */
	public AdminVenueResponse.Impact getImpact(Long venueId) {
		return transactionTemplate.execute(status -> {
			Venue venue = venueService.get(venueId);
			List<PerformanceSchedule> targets = sellerPerformanceService.findCancelableSchedulesOfVenue(venueId,
				LocalDateTime.now());
			List<Performance> drafts = sellerPerformanceService.findDraftsOfVenue(venueId);
			List<PerformanceBookingCount> counts = bookingService.countByPerformance(
				targets.stream().map(PerformanceSchedule::getId).toList());

			Set<Performance> published = new LinkedHashSet<>(
				targets.stream().map(PerformanceSchedule::getPerformance).toList());
			List<AdminVenueResponse.ImpactPerformance> performances = Stream.concat(published.stream(), drafts.stream())
				.map(performance -> impactPerformance(performance, targets, counts))
				.sorted(Comparator.comparingLong(AdminVenueResponse.ImpactPerformance::refundTargetBookingCount)
					.reversed()
					.thenComparing(AdminVenueResponse.ImpactPerformance::performanceId))
				.toList();
			return AdminVenueResponse.Impact.builder()
				.venueId(venueId)
				.name(venue.getName())
				.status(venue.getStatus())
				.upcomingScheduleCount(targets.size())
				.refundTargetBookingCount(counts.stream().mapToLong(PerformanceBookingCount::getRefundTargetCount).sum())
				.pendingBookingCount(counts.stream().mapToLong(PerformanceBookingCount::getPendingCount).sum())
				.draftPerformanceCount(drafts.size())
				.performances(performances)
				.build();
		});
	}

	private AdminVenueResponse.ImpactPerformance impactPerformance(Performance performance,
		List<PerformanceSchedule> targets, List<PerformanceBookingCount> counts) {
		return AdminVenueResponse.ImpactPerformance.builder()
			.performanceId(performance.getId())
			.title(performance.getTitle())
			.status(performance.getStatus())
			.businessId(performance.getBusinessId())
			.businessName(performance.getBusiness().getCompanyName())
			.upcomingScheduleCount((int)targets.stream()
				.filter(schedule -> schedule.getPerformance().getId().equals(performance.getId()))
				.count())
			.refundTargetBookingCount(counts.stream()
				.filter(count -> count.getPerformanceId().equals(performance.getId()))
				.mapToLong(PerformanceBookingCount::getRefundTargetCount)
				.sum())
			.build();
	}

	/**
	 * 공연장을 사용 중지한다. 새 일정만 막으면 공연장 상태만 바꾸고, 전부 취소하면 대상 회차까지 한 트랜잭션으로 취소한다.
	 * 검사 순서: 요청 형식(DB 전) → 공연장 404 → (새 일정만 막기) 이미 중지 409 / (전부 취소) 락 → 대상 재확인 → 이미 중지인데 취소할 것 없음 409.
	 */
	public AdminVenueResponse.Deactivated deactivate(Long adminId, Long venueId, AdminVenueRequest.Deactivate request) {
		if (request.mode() == VenueDeactivationMode.BLOCK_NEW) {
			return blockNew(adminId, venueId, request.reason());
		}
		// 없는 공연장은 락을 잡기 전에 404 로 끝낸다. 락 이름에 쓸 공연 번호는 여기서 읽고 락 안에서 다시 비교한다.
		venueService.get(venueId);
		List<Long> performanceIds = sellerPerformanceService.findPublishedIdsOfVenue(venueId);
		List<String> lockNames = new ArrayList<>();
		lockNames.add(VenueLockNames.venue(venueId));
		performanceIds.forEach(id -> lockNames.add(PerformanceLockNames.performance(id)));

		CancelAllResult result = lockExecutor.execute(lockNames, properties.lockWait(),
			() -> transactionTemplate.execute(
				status -> cancelAllInTransaction(adminId, venueId, performanceIds, request.reason())));
		// 예매자 공연 상세가 취소를 바로 반영하게 커밋·락 해제 뒤에 지운다.
		result.changedPerformanceIds().forEach(userPerformanceCache::evictDetail);
		return result.response();
	}

	private AdminVenueResponse.Deactivated blockNew(Long adminId, Long venueId, String reason) {
		return lockExecutor.execute(List.of(VenueLockNames.venue(venueId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				String name = venueService.get(venueId).getName();
				venueService.deactivate(venueId, LocalDateTime.now());
				saveVenueHistory(adminId, venueId, "DEACTIVATE", VenueStatus.ACTIVE,
					Map.of("status", VenueStatus.INACTIVE, "mode", VenueDeactivationMode.BLOCK_NEW, "reason", reason));
				return deactivated(venueId, name, VenueDeactivationMode.BLOCK_NEW, 0, 0, 0);
			}));
	}

	private record CancelAllResult(AdminVenueResponse.Deactivated response, List<Long> changedPerformanceIds) {
	}

	/** 감사 로그 저장이 실패하면 공연장·회차·공연 변경을 모두 되돌린다. */
	private CancelAllResult cancelAllInTransaction(Long adminId, Long venueId, List<Long> lockedIds, String reason) {
		LocalDateTime now = LocalDateTime.now();
		Venue venue = venueService.get(venueId);
		// 락 밖에서 본 대상과 다르면 잡지 않은 공연이 생긴 것이라 처음부터 다시 시도하게 한다.
		if (!sellerPerformanceService.findPublishedIdsOfVenue(venueId).equals(lockedIds)) {
			throw new BusinessException(CommonExceptionCode.LOCK_ACQUISITION_FAILED);
		}
		// 일괄 UPDATE 는 읽어 둔 엔티티에 반영되지 않아, 아래 값들은 바꾸기 전 값이다.
		VenueStatus before = venue.getStatus();
		String name = venue.getName();
		if (before == VenueStatus.ACTIVE) {
			venueService.deactivate(venueId, now);
		}
		String cancelReason = CANCEL_REASON_PREFIX + reason;
		List<PerformanceSchedule> canceled = lockedIds.stream()
			.flatMap(id -> sellerPerformanceService.cancelSchedulesForVenueSuspension(id, cancelReason, now).stream())
			.toList();
		if (before == VenueStatus.INACTIVE && canceled.isEmpty()) {
			throw new BusinessException(VenueExceptionCode.VENUE_NOTHING_TO_CANCEL);
		}
		List<Performance> canceledPerformances = lockedIds.stream()
			.map(id -> sellerPerformanceService.cancelIfNoScheduleLeft(id, now))
			.flatMap(Optional::stream)
			.toList();

		List<Long> scheduleIds = canceled.stream().map(PerformanceSchedule::getId).toList();
		saveVenueHistory(adminId, venueId, "DEACTIVATE", before, Map.of("status", VenueStatus.INACTIVE,
			"mode", VenueDeactivationMode.CANCEL_ALL, "reason", reason, "canceledScheduleIds", scheduleIds));
		canceled.forEach(schedule -> saveHistory(adminId, schedule.getPerformance().getBusinessId(), "SCHEDULE",
			schedule.getId(), "CANCEL",
			Map.of("performanceId", schedule.getPerformance().getId(), "status", schedule.getStatus()),
			Map.of("performanceId", schedule.getPerformance().getId(), "status", ScheduleStatus.CANCELED,
				"reason", cancelReason)));
		canceledPerformances.forEach(performance -> saveHistory(adminId, performance.getBusinessId(), "PERFORMANCE",
			performance.getId(), "CANCEL", Map.of("status", PerformanceStatus.PUBLISHED),
			Map.of("status", PerformanceStatus.CANCELED, "reason", cancelReason)));

		List<Long> changed = Stream.concat(
				canceled.stream().map(schedule -> schedule.getPerformance().getId()),
				canceledPerformances.stream().map(Performance::getId))
			.distinct()
			.toList();
		return new CancelAllResult(deactivated(venueId, name, VenueDeactivationMode.CANCEL_ALL, canceled.size(),
			canceledPerformances.size(), bookingService.countRefundTargets(scheduleIds)), changed);
	}

	private AdminVenueResponse.Deactivated deactivated(Long venueId, String name, VenueDeactivationMode mode,
		int scheduleCount, int performanceCount, long refundCount) {
		return AdminVenueResponse.Deactivated.builder()
			.venueId(venueId)
			.name(name)
			.status(VenueStatus.INACTIVE)
			.mode(mode)
			.canceledScheduleCount(scheduleCount)
			.canceledPerformanceCount(performanceCount)
			.refundRequestedCount(refundCount)
			.build();
	}

	/** 사용을 재개한다. 전부 취소로 취소한 회차와 공연은 되돌리지 않는다. */
	public AdminVenueResponse.Activated activate(Long adminId, Long venueId) {
		return lockExecutor.execute(List.of(VenueLockNames.venue(venueId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				String name = venueService.get(venueId).getName();
				venueService.activate(venueId, LocalDateTime.now());
				saveVenueHistory(adminId, venueId, "ACTIVATE", VenueStatus.INACTIVE,
					Map.of("status", VenueStatus.ACTIVE));
				return new AdminVenueResponse.Activated(venueId, name, VenueStatus.ACTIVE);
			}));
	}

	private void saveVenueHistory(Long adminId, Long venueId, String action, VenueStatus before,
		Map<String, Object> after) {
		saveHistory(adminId, null, TARGET_VENUE, venueId, action, Map.of("status", before), after);
	}

	/** 관리자 행동 한 줄. 회차·공연에는 그 공연의 사업체 번호를 넣어 판매자가 자기 이력에서 볼 수 있게 한다. */
	private void saveHistory(Long adminId, Long businessId, String targetType, Long targetId, String action,
		Object before, Object after) {
		auditLogService.saveHistory(AuditLogCommand.builder()
			.actorType(ActorType.ADMIN)
			.actorId(adminId)
			.businessId(businessId)
			.targetType(targetType)
			.targetId(targetId)
			.action(action)
			.before(before)
			.after(after)
			.build());
	}
}
