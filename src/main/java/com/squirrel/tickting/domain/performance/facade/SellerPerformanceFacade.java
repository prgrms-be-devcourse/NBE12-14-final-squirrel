package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.audit.service.AuditLogCommand;
import com.squirrel.tickting.domain.audit.service.AuditLogService;
import com.squirrel.tickting.domain.audit.type.ActorType;
import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.performance.cache.UserPerformanceCache;
import com.squirrel.tickting.domain.performance.dto.SellerCancelPolicyRequest;
import com.squirrel.tickting.domain.performance.dto.SellerCancelPolicyResponse;
import com.squirrel.tickting.domain.performance.dto.SellerGradeRequest;
import com.squirrel.tickting.domain.performance.dto.SellerGradeResponse;
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
import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.domain.venue.service.VenueService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자의 공연·등급·회차·취소 규정 변경. 공연장(venue)·취소 규정(cancel policy)·공연(performance)·구역 설정(grade)·판매 좌석·예매·감사 로그(audit) 서비스를 엮고,
 * 쓰기는 TransactionTemplate 으로 한 트랜잭션에 묶는다. (조회는 공연의 읽기 전용 연관으로 서비스가 직접 응답을 만든다)
 * 감사 로그는 변경과 같은 트랜잭션이라 로그를 남기지 못하면 변경도 취소된다. 실제로 바뀐 것이 없으면 남기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SellerPerformanceFacade {

	private static final String TARGET_PERFORMANCE = "PERFORMANCE";
	private static final String TARGET_SCHEDULE = "SCHEDULE";
	private static final String TARGET_SEAT_GRADE = "SEAT_GRADE";
	private static final String TARGET_CANCEL_POLICY = "CANCEL_POLICY";
	private static final String ACTION_CREATE = "CREATE";
	private static final String ACTION_UPDATE = "UPDATE";

	private final SellerPerformanceService sellerPerformanceService;
	private final UserPerformanceCache userPerformanceCache;
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
	public SellerPerformanceResponse.Updated update(Long businessId, Long actorId, Long performanceId,
		SellerPerformanceRequest.Save request) {
		sellerPerformanceService.validate(request);

		// 남의 공연은 락을 잡기 전에 404 로 끝내서 남의 공연 상태를 드러내지 않는다.
		sellerPerformanceService.getMinePerformance(businessId, performanceId);

		Performance updated = lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)),
			properties.lockWait(), () -> transactionTemplate.execute(
				status -> updateInTransaction(businessId, actorId, performanceId, request)));
		return SellerPerformanceResponse.Updated.from(updated);
	}

	/** 락을 기다리는 사이 상태가 바뀌었을 수 있어 락 밖의 판단은 믿지 않고 다시 읽는다. */
	private Performance updateInTransaction(Long businessId, Long actorId, Long performanceId,
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

		// 엔티티와 이미지를 덮어쓰기 전에 읽어 둔다.
		Map<String, Object> before = performanceValues(performance);
		sellerPerformanceService.update(performance, request, venueChanged ? request.venueId() : null, newCancelPolicy);
		Map<String, Object> after = performanceValues(performance);
		if (venueChanged) {
			// 옛 공연장 기준의 구역 설정과 판매 좌석은 의미가 없다. 좌석은 스케줄러가 새 공연장 기준으로 다시 만든다.
			gradeService.resetZones(performance, newZoneIds);
			scheduleSeatService.deleteAllOfPerformance(performanceId, now);
		}
		saveUpdateHistory(businessId, actorId, performanceId, before, after, venueChanged);
		return performance;
	}

	/** 감사 로그에 남길 공연 값. 비워 둔 값(null)도 담아야 해서 Map.of 를 쓰지 않는다. */
	private Map<String, Object> performanceValues(Performance performance) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("venueId", performance.getVenueId());
		values.put("cancelPolicyId", performance.getCancelPolicy().getId());
		values.put("title", performance.getTitle());
		values.put("genre", performance.getGenre());
		values.put("ageLimit", performance.getAgeLimit());
		values.put("runningTime", performance.getRunningTime());
		values.put("castInfo", performance.getCastInfo());
		values.put("posterUrl", performance.getPosterUrl());
		values.put("notice", performance.getNotice());
		values.put("guide", performance.getGuide());
		values.put("saleOpenAt", performance.getSaleOpenAt());
		values.put("saleCloseAt", performance.getSaleCloseAt());
		values.put("maxPerPerson", performance.getMaxPerPerson());
		values.put("receiveType", performance.getReceiveType());
		values.put("bookingFee", performance.getBookingFee());
		values.put("images", sellerPerformanceService.getImages(performance.getId()));
		return values;
	}

	/** 바뀐 항목만 남긴다. 공연장을 바꾸면 구역 설정과 판매 좌석이 함께 지워지므로 그 사실도 적는다. */
	private void saveUpdateHistory(Long businessId, Long actorId, Long performanceId, Map<String, Object> before,
		Map<String, Object> after, boolean venueChanged) {
		before.keySet().removeIf(key -> Objects.equals(before.get(key), after.get(key)));
		after.keySet().retainAll(before.keySet());
		if (before.isEmpty()) {
			return;
		}
		if (venueChanged) {
			before.put("zonesAndSeatsReset", false);
			after.put("zonesAndSeatsReset", true);
		}
		saveHistory(businessId, actorId, TARGET_PERFORMANCE, performanceId, ACTION_UPDATE, before, after);
	}

	/**
	 * 작성 중인 내 공연을 공개한다. 공개하면 구역·등급·취소 규정을 되돌릴 수 없다.
	 * 락은 공개 직후 구역·등급(SLR-10·11)이나 취소 규정(SLR-22)이 바뀌어 공개 조건이 깨지는 것을 막으려는 것이다.
	 * 공연 락과, 기본 규정이 아니면 그 공연의 규정 락을 함께 잡는다. (실행기가 이름순으로 잡아 데드락이 없다)
	 */
	public SellerPerformanceResponse.Published publish(Long businessId, Long actorId, Long performanceId) {
		// 남의 공연은 락을 잡기 전에 404 로 끝낸다. 규정 번호는 락 이름에 필요해서 락 밖에서 읽고, 바뀌면 안에서 알아챈다.
		Long cancelPolicyId = sellerPerformanceService.getPublishLockPolicyId(businessId, performanceId);

		List<String> lockNames = new ArrayList<>();
		lockNames.add(PerformanceLockNames.performance(performanceId));
		if (cancelPolicyId != null) {
			lockNames.add(PerformanceLockNames.cancelPolicy(cancelPolicyId));
		}
		return lockExecutor.execute(lockNames, properties.lockWait(), () -> transactionTemplate.execute(status -> {
			SellerPerformanceResponse.Published published = sellerPerformanceService.publish(businessId, performanceId,
				cancelPolicyId);
			saveHistory(businessId, actorId, TARGET_PERFORMANCE, performanceId, "PUBLISH",
				Map.of("status", PerformanceStatus.DRAFT), Map.of("status", published.status()));
			return published;
		}));
	}

	/**
	 * 내 공연에 등급을 추가한다. 락은 쓰지 않는다. 새 등급은 아직 어느 구역에도 지정되지 않아 판매 좌석 생성이 그 가격을 복사할 일이 없고,
	 * 같은 등급의 동시 추가는 나중에 만들 UNIQUE (performance_id, name) 가 마지막 방어선이다. (취소 규정 이름과 같은 방식)
	 */
	public SellerGradeResponse.Grade addGrade(Long businessId, Long actorId, Long performanceId,
		SellerGradeRequest.Save request) {
		return transactionTemplate.execute(status -> {
			SellerGradeResponse.Grade added = gradeService.add(businessId, performanceId, request);
			saveHistory(businessId, actorId, TARGET_SEAT_GRADE, added.gradeId(), ACTION_CREATE, null,
				gradeValues(performanceId, added));
			return added;
		});
	}

	/**
	 * 내 공연의 등급을 전체 교체로 고친다. 락은 판매 좌석 생성이 고치기 전 가격을 복사하는 것을 막으려는 것이다.
	 * 남의 공연은 락을 잡기 전에 404 로 끝내서 남의 공연 상태(취소·locked)를 드러내지 않는다.
	 */
	public SellerGradeResponse.Grade updateGrade(Long businessId, Long actorId, Long performanceId, Long gradeId,
		SellerGradeRequest.Save request) {
		sellerPerformanceService.getMinePerformance(businessId, performanceId);
		return lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				// 고치기 전 값. 없는 등급이면 비어 있고, 아래 수정이 정해진 순서대로 검사해 404 를 낸다.
				var found = gradeService.findGrade(performanceId, gradeId);
				SellerGradeResponse.Grade updated = gradeService.update(businessId, performanceId, gradeId, request);
				SellerGradeResponse.Grade previous = found.orElseThrow();
				if (!previous.equals(updated)) {
					saveHistory(businessId, actorId, TARGET_SEAT_GRADE, gradeId, ACTION_UPDATE,
						gradeValues(performanceId, previous), gradeValues(performanceId, updated));
				}
				return updated;
			}));
	}

	private Map<String, Object> gradeValues(Long performanceId, SellerGradeResponse.Grade grade) {
		return Map.of("performanceId", performanceId, "name", grade.name(), "price", grade.price());
	}

	/**
	 * 내 공연의 구역 상태와 등급을 바꾼다. 락은 판매 좌석 생성이 고치기 전 구역 지정(그리고 가격)을 복사하는 것을 막으려는 것이다.
	 * 요청만 보는 검사는 DB 조회 전에 하고, 남의 공연은 락을 잡기 전에 404 로 끝낸다.
	 */
	public SellerGradeResponse.Overview updateZoneGrades(Long businessId, Long actorId, Long performanceId,
		SellerGradeRequest.ZoneAssign request) {
		gradeService.validateAssignments(request.assignments());
		sellerPerformanceService.getMinePerformance(businessId, performanceId);
		return lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				List<ZoneValue> before = zoneValues(gradeService.getOverview(businessId, performanceId));
				SellerGradeResponse.Overview updated = gradeService.updateZoneGrades(businessId, performanceId, request);
				saveZoneHistory(businessId, actorId, performanceId, before, zoneValues(updated));
				return updated;
			}));
	}

	/** 감사 로그에 남길 구역 설정. 등급 번호만으로는 읽기 어려워 등급명을 적는다. */
	private record ZoneValue(Long zoneId, String zoneName, ZoneAssignmentStatus assignmentStatus,
		SeatGradeName gradeName) {
	}

	private List<ZoneValue> zoneValues(SellerGradeResponse.Overview overview) {
		return overview.zones().stream()
			.map(zone -> new ZoneValue(zone.zoneId(), zone.name(), zone.assignmentStatus(), overview.grades().stream()
				.filter(grade -> grade.gradeId().equals(zone.gradeId()))
				.map(SellerGradeResponse.Grade::name)
				.findFirst().orElse(null)))
			.toList();
	}

	/** 요청 한 번에 한 줄이고 바뀐 구역만 담는다. */
	private void saveZoneHistory(Long businessId, Long actorId, Long performanceId, List<ZoneValue> before,
		List<ZoneValue> after) {
		List<ZoneValue> changedBefore = before.stream().filter(zone -> !after.contains(zone)).toList();
		if (changedBefore.isEmpty()) {
			return;
		}
		List<ZoneValue> changedAfter = after.stream().filter(zone -> !before.contains(zone)).toList();
		saveHistory(businessId, actorId, TARGET_PERFORMANCE, performanceId, "ZONE_ASSIGN",
			Map.of("zones", changedBefore), Map.of("zones", changedAfter));
	}

	/**
	 * 내 공연에 회차를 하나 추가한다. 공연 락은 공개·공연 수정·등급과 구역 수정·좌석 생성과 같은 이름이라, 확인한 공연 값이 저장 전에 바뀌지 않는다.
	 * 서로 다른 공연이 같은 순간에 같은 공연장의 같은 시간을 잡는 것은 공연 락으로 막지 못한다. (드물다고 보고 감수, DB 제약은 TODO)
	 * 남의 공연은 락을 잡기 전에 404 로 끝낸다.
	 */
	public SellerPerformanceResponse.ScheduleCreated addSchedule(Long businessId, Long actorId, Long performanceId,
		SellerPerformanceRequest.ScheduleSave request) {
		sellerPerformanceService.getMinePerformance(businessId, performanceId);
		return lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				SellerPerformanceResponse.ScheduleCreated created = sellerPerformanceService.addSchedule(businessId,
					performanceId, request.startAt());
				saveHistory(businessId, actorId, TARGET_SCHEDULE, created.scheduleId(), ACTION_CREATE, null,
					Map.of("performanceId", performanceId, "startAt", created.startAt(),
						"seatsGenerated", created.seatsGeneratedAt() != null));
				return created;
			}));
	}

	/**
	 * 회차 판매를 일시 중지하고 바뀐 회차마다 감사 로그를 남긴다. 공연 락은 회차 추가·수정, 공연 수정, 좌석 생성과 같은 이름이다.
	 * 좌석 점유는 이 락을 잡지 않지만, 중지 순간에 통과한 점유는 결제에서 막힌다. 대기열(Redis)은 건드리지 않는다.
	 * 검사 순서: 요청 형식(DB 전) → 공연 조회 404(락 밖) → 락 → 서비스의 검사.
	 */
	public SellerPerformanceResponse.ScheduleSaleChanged pauseSchedules(Long businessId, Long actorId,
		Long performanceId, SellerPerformanceRequest.SchedulePause request) {
		sellerPerformanceService.validateScheduleSelection(request.all(), request.scheduleIds());
		sellerPerformanceService.getMinePerformance(businessId, performanceId);
		SellerPerformanceResponse.ScheduleSaleChanged response = lockExecutor.execute(
			List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				SellerPerformanceService.ScheduleSaleChange change = sellerPerformanceService.pauseSchedules(businessId,
					performanceId, request);
				for (PerformanceSchedule schedule : change.changed()) {
					saveHistory(businessId, actorId, TARGET_SCHEDULE, schedule.getId(), "PAUSE",
						saleValues(performanceId, ScheduleStatus.ON_SALE, null),
						saleValues(performanceId, ScheduleStatus.PAUSED, request.reason()));
				}
				return change.response();
			}));
		// 커밋한 뒤에 지워야 지우자마자 읽은 요청이 이전 값을 다시 저장하지 않는다.
		userPerformanceCache.evictDetail(performanceId);
		return response;
	}

	/** 일시 중지한 회차를 재개한다. 지운 중지 사유는 감사 로그의 바꾸기 전 값에 남는다. 락과 검사 순서는 중지와 같다. */
	public SellerPerformanceResponse.ScheduleSaleChanged resumeSchedules(Long businessId, Long actorId,
		Long performanceId, SellerPerformanceRequest.ScheduleResume request) {
		sellerPerformanceService.validateScheduleSelection(request.all(), request.scheduleIds());
		sellerPerformanceService.getMinePerformance(businessId, performanceId);
		SellerPerformanceResponse.ScheduleSaleChanged response = lockExecutor.execute(
			List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				SellerPerformanceService.ScheduleSaleChange change = sellerPerformanceService.resumeSchedules(businessId,
					performanceId, request);
				for (PerformanceSchedule schedule : change.changed()) {
					saveHistory(businessId, actorId, TARGET_SCHEDULE, schedule.getId(), "RESUME",
						saleValues(performanceId, ScheduleStatus.PAUSED, schedule.getPauseReason()),
						saleValues(performanceId, ScheduleStatus.ON_SALE, null));
				}
				return change.response();
			}));
		// 커밋한 뒤에 지워야 지우자마자 읽은 요청이 이전 값을 다시 저장하지 않는다.
		userPerformanceCache.evictDetail(performanceId);
		return response;
	}

	/**
	 * 회차를 연기하고 감사 로그를 남긴다. 공연 락은 회차 추가·수정·중지, 공연 수정, 좌석 생성과 같은 이름이다.
	 * 좌석 점유와 결제 승인은 이 락을 잡지 않아, 확인을 막 통과한 요청은 막지 못한다. (알고 감수, doc/seller-progress.md TODO)
	 * 대기열(Redis)은 건드리지 않는다.
	 * 검사 순서: 요청 형식·마감(DB 전) → 회차 조회 404(락 밖) → 락 → 서비스의 검사.
	 */
	public SellerPerformanceResponse.SchedulePostponed postponeSchedule(Long businessId, Long actorId, Long scheduleId,
		SellerPerformanceRequest.SchedulePostpone request) {
		sellerPerformanceService.validateFreeCancelUntil(request.newStartAt(), request.freeCancelUntil());
		return changePostponement(businessId, actorId, scheduleId, "POSTPONE",
			() -> sellerPerformanceService.postpone(businessId, actorId, scheduleId, request));
	}

	/** 일정 미정으로 연기한 회차의 새 일정을 확정하고 감사 로그를 남긴다. 락과 검사 순서는 연기와 같다. */
	public SellerPerformanceResponse.SchedulePostponed confirmPostponement(Long businessId, Long actorId,
		Long scheduleId, SellerPerformanceRequest.SchedulePostponeConfirm request) {
		sellerPerformanceService.validateFreeCancelUntil(request.newStartAt(), request.freeCancelUntil());
		return changePostponement(businessId, actorId, scheduleId, "POSTPONE_CONFIRM",
			() -> sellerPerformanceService.confirmPostponement(businessId, scheduleId, request));
	}

	private SellerPerformanceResponse.SchedulePostponed changePostponement(Long businessId, Long actorId,
		Long scheduleId, String action, Supplier<SellerPerformanceService.PostponementChange> change) {
		// 남의 회차는 락을 잡기 전에 404 로 끝낸다. 락 이름에 쓸 공연 번호도 여기서 얻는다.
		Long performanceId = sellerPerformanceService.getMySchedule(businessId, scheduleId).getPerformance().getId();
		SellerPerformanceResponse.SchedulePostponed response = lockExecutor.execute(
			List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				SellerPerformanceService.PostponementChange changed = change.get();
				saveHistory(businessId, actorId, TARGET_SCHEDULE, scheduleId, action,
					Map.of("performanceId", changed.performanceId(), "status", changed.previousStatus(),
						"startAt", changed.previousStartAt()),
					postponementValues(changed));
				return changed.response();
			}));
		// 커밋한 뒤에 지워야 지우자마자 읽은 요청이 이전 값을 다시 저장하지 않는다.
		userPerformanceCache.evictDetail(performanceId);
		return response;
	}

	/** 연기·확정 로그의 바꾼 뒤 값. 일정 미정이면 일시와 마감이 null 이라 Map.of 를 쓰지 않는다. */
	private Map<String, Object> postponementValues(SellerPerformanceService.PostponementChange changed) {
		SellerPerformanceResponse.Postponement postponement = changed.response().postponement();
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("performanceId", changed.performanceId());
		values.put("status", changed.response().status());
		values.put("startAt", postponement.newStartAt());
		values.put("reason", postponement.reason());
		values.put("freeCancelUntil", postponement.freeCancelUntil());
		return values;
	}

	/** 중지·재개 로그의 전후 값. 사유가 없는 쪽에는 reason 을 넣지 않는다. */
	private Map<String, Object> saleValues(Long performanceId, ScheduleStatus status, String reason) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("performanceId", performanceId);
		values.put("status", status);
		if (reason != null) {
			values.put("reason", reason);
		}
		return values;
	}

	/**
	 * 회차의 공연 일시를 바꾼다. 예매 확인이 예매 도메인이라 파사드가 맡는다.
	 * 락은 공연 단위(performance:{번호})라 같은 공연의 회차 추가·공연 수정·공개·판매 좌석 생성과 겹치지 않는다.
	 * 좌석 점유는 이 락을 잡지 않아, 예매를 확인한 직후 저장하기 전에 들어온 점유는 막지 못한다. (알고 감수, doc/seller-progress.md TODO)
	 * 검사 순서: 회차 조회 404(락 밖) → 락 → 재조회 → 공연·회차 상태 → 같은 일시 → 새 일시 → (공개) 예매 → 판매 종료 → 겹침.
	 * 앞에서 막히면 뒤의 쿼리는 나가지 않는다. 처리량은 추정이고 측정하지 않았다.
	 */
	public SellerPerformanceResponse.ScheduleUpdated updateSchedule(Long businessId, Long actorId, Long scheduleId,
		SellerPerformanceRequest.ScheduleSave request) {
		// 남의 회차는 락을 잡기 전에 404 로 끝낸다. 락 이름에 쓸 공연 번호도 여기서 얻는다.
		Long performanceId = sellerPerformanceService.getMySchedule(businessId, scheduleId).getPerformance().getId();

		return lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(
				status -> updateScheduleInTransaction(businessId, actorId, scheduleId, request.startAt())));
	}

	/** 락을 기다리는 사이 상태가 바뀌었을 수 있어 회차를 다시 읽는다. */
	private SellerPerformanceResponse.ScheduleUpdated updateScheduleInTransaction(Long businessId, Long actorId,
		Long scheduleId, LocalDateTime startAt) {
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
		// 저장은 일괄 UPDATE 라 바꾸기 전 값을 먼저 읽어 둔다.
		Long performanceId = performance.getId();
		LocalDateTime previousStartAt = schedule.getStartAt();
		SellerPerformanceResponse.ScheduleUpdated updated = sellerPerformanceService.reschedule(businessId, schedule,
			startAt, now);
		saveHistory(businessId, actorId, TARGET_SCHEDULE, scheduleId, ACTION_UPDATE,
			Map.of("performanceId", performanceId, "startAt", previousStartAt),
			Map.of("performanceId", performanceId, "startAt", startAt));
		return updated;
	}

	/** 취소 규정을 만들고 감사 로그를 남긴다. 새 규정은 쓰는 공연이 없어 잠금은 필요 없다. */
	public SellerCancelPolicyResponse.Detail createCancelPolicy(Long businessId, Long actorId,
		SellerCancelPolicyRequest.Save request) {
		return transactionTemplate.execute(status -> {
			SellerCancelPolicyResponse.Detail created = cancelPolicyService.create(businessId, request);
			saveHistory(businessId, actorId, TARGET_CANCEL_POLICY, created.cancelPolicyId(), ACTION_CREATE, null,
				Map.of("name", created.name(), "rules", created.rules()));
			return created;
		});
	}

	/**
	 * 취소 규정을 수정하고 바뀐 항목만 감사 로그에 남긴다. 값이 그대로면(구간을 보낸 순서만 다른 경우 포함) 남기지 않는다.
	 * 구간은 지운 뒤 다시 넣으므로 바꾸기 전 값은 엔티티가 아니라 응답 값으로 먼저 읽어 둔다.
	 * 검사 순서: 구간 → 내 규정(404·기본 규정 409, 락 밖) → 락 → 서비스의 검사·교체.
	 */
	public SellerCancelPolicyResponse.Detail updateCancelPolicy(Long businessId, Long actorId, Long cancelPolicyId,
		SellerCancelPolicyRequest.Save request) {
		List<SellerCancelPolicyRequest.Rule> sorted = cancelPolicyService.validateUpdateRequest(cancelPolicyId,
			businessId, request);
		return lockExecutor.execute(List.of(PerformanceLockNames.cancelPolicy(cancelPolicyId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> {
				SellerCancelPolicyResponse.Detail before = cancelPolicyService.getVisibleDetail(cancelPolicyId,
					businessId);
				SellerCancelPolicyResponse.Detail updated = cancelPolicyService.update(cancelPolicyId, businessId,
					request, sorted);
				saveCancelPolicyHistory(businessId, actorId, before, updated);
				return updated;
			}));
	}

	private void saveCancelPolicyHistory(Long businessId, Long actorId, SellerCancelPolicyResponse.Detail before,
		SellerCancelPolicyResponse.Detail after) {
		Map<String, Object> changedBefore = new LinkedHashMap<>();
		Map<String, Object> changedAfter = new LinkedHashMap<>();
		if (!before.name().equals(after.name())) {
			changedBefore.put("name", before.name());
			changedAfter.put("name", after.name());
		}
		// 두 목록 모두 공연에서 먼 구간부터라 순서를 맞출 필요가 없다.
		if (!before.rules().equals(after.rules())) {
			changedBefore.put("rules", before.rules());
			changedAfter.put("rules", after.rules());
		}
		if (!changedBefore.isEmpty()) {
			saveHistory(businessId, actorId, TARGET_CANCEL_POLICY, after.cancelPolicyId(), ACTION_UPDATE,
				changedBefore, changedAfter);
		}
	}

	/** 판매자 행동 한 줄. 사업체 번호를 채워야 판매자가 자기 사업체 이력으로 찾을 수 있다. */
	private void saveHistory(Long businessId, Long actorId, String targetType, Long targetId, String action,
		Object before, Object after) {
		auditLogService.saveHistory(AuditLogCommand.builder()
			.actorType(ActorType.SELLER)
			.actorId(actorId)
			.businessId(businessId)
			.targetType(targetType)
			.targetId(targetId)
			.action(action)
			.before(before)
			.after(after)
			.build());
	}
}
