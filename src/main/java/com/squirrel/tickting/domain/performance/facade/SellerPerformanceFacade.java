package com.squirrel.tickting.domain.performance.facade;

import com.squirrel.tickting.domain.booking.service.BookingService;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceRequest;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.service.CancelPolicyService;
import com.squirrel.tickting.domain.performance.service.GradeService;
import com.squirrel.tickting.domain.performance.service.PerformanceLockNames;
import com.squirrel.tickting.domain.performance.service.PerformanceProperties;
import com.squirrel.tickting.domain.performance.service.PerformanceSaveCommand;
import com.squirrel.tickting.domain.performance.service.ScheduleSeatService;
import com.squirrel.tickting.domain.performance.service.SellerPerformanceService;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.venue.service.VenueService;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 판매자의 공연 등록·수정. 공연장(venue)·취소 규정(cancel policy)·공연(performance)·구역 설정(grade)·판매 좌석·예매 서비스를 엮고,
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
				request.toCommand());
			gradeService.initializeZones(created, zoneIds);
			return created;
		});
		return SellerPerformanceResponse.Created.from(performance);
	}

	/**
	 * 공연을 전체 교체로 수정한다. 락은 커밋 뒤에 풀려야 해서 락을 먼저 잡고 그 안에서 트랜잭션을 연다.
	 * 락은 공연 단위(performance:{번호})이고, 공연장을 바꾸거나 러닝타임을 늘릴 때는 그 공연장의 시간을 차지하므로
	 * 공연장 락(venue:{번호})도 함께 잡는다. (공연장 변경은 새 공연장, 러닝타임은 지금 공연장. 실행기가 이름순으로 잡아 데드락이 없다)
	 * 공연 락은 공연마다 달라서, 서로 다른 공연이 같은 공연장에 동시에 들어와 시간이 겹치는 것은 공연장 락만 막을 수 있다.
	 * 작성 중 취소 규정 변경은 규정 락을 잡지 않는다. 작성 중 공연이 쓰는 규정은 잠기지 않아 규정 수정과 겹쳐도 규칙 위반이 아니고,
	 * 공개와는 공연 락으로 막힌다.
	 * 검사 순서: 요청 형식(DB 전) → 공연 조회 404(락 밖) → 락 → 재조회 → 공연 한 행 → 규정·공연장 한 건 조회 → 회차 → 예매 → 회차 겹침.
	 * 앞에서 막히면 뒤의 쿼리는 나가지 않게 처리량이 적은 것(한 행 → 한 건 → 작은 목록 → 겹침 쿼리)부터 두었고, 측정하지는 않았다.
	 */
	public SellerPerformanceResponse.Updated update(Long businessId, Long performanceId,
		SellerPerformanceRequest.Save request) {
		PerformanceSaveCommand command = request.toCommand();
		sellerPerformanceService.validate(command);

		// 락 이름을 정하려고 락 밖에서 한 번 읽는다. 남의 공연은 여기서 404 로 끝나 락을 잡지 않는다.
		Performance current = sellerPerformanceService.getMinePerformance(businessId, performanceId);
		Long lockedVenueId = current.getVenueId();
		Integer lockedRunningTime = current.getRunningTime();

		List<String> lockNames = new ArrayList<>();
		lockNames.add(PerformanceLockNames.performance(performanceId));
		if (!lockedVenueId.equals(request.venueId())) {
			lockNames.add(PerformanceLockNames.venue(request.venueId()));
		} else if (command.runningTime() > lockedRunningTime) {
			lockNames.add(PerformanceLockNames.venue(lockedVenueId));
		}

		Performance updated = lockExecutor.execute(lockNames, properties.lockWait(), () -> transactionTemplate.execute(
			status -> updateInTransaction(businessId, performanceId, request, command, lockedVenueId, lockedRunningTime)));
		return SellerPerformanceResponse.Updated.from(updated);
	}

	/** 락을 기다리는 사이 상태가 바뀌었을 수 있어 락 밖의 판단은 믿지 않고 다시 읽는다. */
	private Performance updateInTransaction(Long businessId, Long performanceId,
		SellerPerformanceRequest.Save request, PerformanceSaveCommand command, Long lockedVenueId,
		Integer lockedRunningTime) {
		// 현재 시각은 한 번만 읽어 모든 비교에 같은 값을 쓴다.
		LocalDateTime now = LocalDateTime.now();
		Performance performance = sellerPerformanceService.getMinePerformance(businessId, performanceId);

		// 락 밖에서 본 공연장·러닝타임과 다르면 우리가 잡은 공연장 락이 지금 필요한 락이 아니다. 처음부터 다시 시도하게 한다.
		if (!performance.getVenueId().equals(lockedVenueId) || !performance.getRunningTime().equals(lockedRunningTime)) {
			throw new BusinessException(CommonExceptionCode.LOCK_ACQUISITION_FAILED);
		}
		sellerPerformanceService.validateUpdatable(performance, command, request.venueId(), request.cancelPolicyId(), now);

		boolean venueChanged = !performance.getVenueId().equals(request.venueId());
		boolean cancelPolicyChanged = !performance.getCancelPolicy().getId().equals(request.cancelPolicyId());
		// 공개된 공연은 위에서 이미 막혔으므로 아래 두 확인은 작성 중 공연에서만 일어난다.
		CancelPolicy newCancelPolicy = cancelPolicyChanged
			? cancelPolicyService.getVisible(request.cancelPolicyId(), businessId) : null;
		List<Long> newZoneIds = venueChanged ? venueService.getActiveZoneIds(request.venueId()) : null;

		sellerPerformanceService.validateScheduleRules(performance, command);
		// 작성 중 공연은 예매가 있을 수 없어 묻지 않는다.
		boolean maxPerPersonReduced = performance.getStatus() == PerformanceStatus.PUBLISHED
			&& command.maxPerPerson() < performance.getMaxPerPerson();
		if (maxPerPersonReduced && bookingService.existsOccupying(performanceId)) {
			throw new BusinessException(PerformanceExceptionCode.MAX_PER_PERSON_REDUCE_NOT_ALLOWED);
		}
		boolean runningTimeIncreased = command.runningTime() > performance.getRunningTime();
		if (venueChanged || runningTimeIncreased) {
			Long targetVenueId = venueChanged ? request.venueId() : performance.getVenueId();
			sellerPerformanceService.validateNoOverlap(performanceId, targetVenueId, command.runningTime());
		}

		sellerPerformanceService.update(performance, command, venueChanged ? request.venueId() : null, newCancelPolicy);
		if (venueChanged) {
			// 옛 공연장 기준의 구역 설정과 판매 좌석은 의미가 없다. 좌석은 스케줄러가 새 공연장 기준으로 다시 만든다.
			gradeService.resetZones(performance, newZoneIds);
			scheduleSeatService.deleteAllOfPerformance(performanceId, now);
		}
		return performance;
	}
}
