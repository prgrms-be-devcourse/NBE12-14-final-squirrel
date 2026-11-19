package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceImageRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ZoneGradeRepository;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.domain.venue.infra.entity.VenueZone;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class SellerPerformanceService {

	/**
	 * 판매 오픈까지 이만큼 남아야 공개할 수 있다. (임시)
	 * 판매 좌석은 오픈 24시간 전부터 스케줄러가 1시간마다 만든다. 공개 뒤 오픈 전에 스케줄러가 최소 두 번 돌아
	 * 첫 생성이 실패해도 다시 만들 수 있게 한 값이다. 스케줄러 주기가 바뀌면 이 값도 다시 정한다.
	 */
	private static final Duration PUBLISH_LEAD_TIME = Duration.ofHours(3);
	private static final DateTimeFormatter SCHEDULE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final PerformanceRepository performanceRepository;
	private final PerformanceImageRepository performanceImageRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;
	private final ZoneGradeRepository zoneGradeRepository;
	private final DistributedLockExecutor lockExecutor;
	private final TransactionTemplate transactionTemplate;
	private final PerformanceProperties properties;

	/**
	 * 내 사업체의 공연 목록. 상태를 주지 않으면 취소된 공연까지 모든 상태가 나온다.
	 * 공연장명과 회차 수는 공연의 읽기 전용 연관(공연장, 회차)에서 꺼낸다. 지연 로딩이라 응답을 이 트랜잭션 안에서 만든다.
	 */
	@Transactional(readOnly = true)
	public Page<SellerPerformanceResponse.Summary> searchMine(Long businessId, PerformanceStatus status, Pageable pageable) {
		var statuses = status == null ? EnumSet.allOf(PerformanceStatus.class) : EnumSet.of(status);
		return performanceRepository.search(businessId, statuses, pageable).map(SellerPerformanceResponse.Summary::from);
	}

	/** 내 사업체의 공연 상세. 남의 공연은 없는 공연과 같은 404로 답한다. */
	@Transactional(readOnly = true)
	public SellerPerformanceResponse.Detail getMine(Long businessId, Long performanceId) {
		Performance performance = getMineEntity(businessId, performanceId);
		return SellerPerformanceResponse.Detail.from(new PerformanceDetail(
			performance,
			performanceImageRepository.findByPerformanceIdOrderBySortOrder(performanceId),
			performanceScheduleRepository.findByPerformanceIdOrderByStartAt(performanceId)));
	}

	/**
	 * 작성 중인 내 공연을 공개(PUBLISHED)한다. 공개하면 구역·등급·취소 규정을 되돌릴 수 없어서 조건을 모두 만족해야 한다.
	 * 락은 공개 직후 구역·등급(SLR-10·11)이나 취소 규정(SLR-22)이 바뀌어 공개 조건이 깨지는 것을 막으려는 것이다.
	 * 공연 락과, 기본 규정이 아니면 그 공연의 규정 락을 함께 잡고(실행기가 이름순으로 잡아 데드락이 없다)
	 * 락은 커밋 뒤에 풀려야 해서 이 메서드에는 @Transactional 을 붙이지 않는다.
	 * 남의 공연은 락을 잡기 전에 404 로 끝내서 남의 공연 상태를 드러내지 않는다.
	 */
	public SellerPerformanceResponse.Published publish(Long businessId, Long performanceId) {
		getMineEntity(businessId, performanceId);
		// 규정 번호는 락 이름에 필요해서 락 밖에서 읽는다. 락을 기다리는 사이 바뀌면 안에서 알아챈다.
		Long cancelPolicyId = performanceRepository.findLockableCancelPolicyId(performanceId).orElse(null);

		List<String> lockNames = new ArrayList<>();
		lockNames.add(PerformanceLockNames.performance(performanceId));
		if (cancelPolicyId != null) {
			lockNames.add(PerformanceLockNames.cancelPolicy(cancelPolicyId));
		}
		return lockExecutor.execute(lockNames, properties.lockWait(),
			() -> transactionTemplate.execute(status -> publishInTransaction(businessId, performanceId, cancelPolicyId)));
	}

	/**
	 * 락을 기다리는 사이 상태가 바뀌었을 수 있어 락 밖의 판단은 믿지 않고 다시 읽는다.
	 * 검사 순서: 공연 한 행 → 회차 → 구역 설정. 앞에서 막히면 뒤의 쿼리는 나가지 않는다.
	 * 회차를 구역보다 먼저 두는 것은 읽을 행 수가 적을 것이라는 추정이고 측정하지는 않았다.
	 */
	private SellerPerformanceResponse.Published publishInTransaction(Long businessId, Long performanceId,
		Long lockedCancelPolicyId) {
		// 현재 시각은 한 번만 읽어 모든 비교에 같은 값을 쓴다.
		LocalDateTime now = LocalDateTime.now();
		Performance performance = getMineEntity(businessId, performanceId);

		// 락 밖에서 본 규정과 다르면 우리가 잡은 규정 락이 지금 규정을 지키지 못한다. 처음부터 다시 시도하게 한다.
		if (!Objects.equals(lockedCancelPolicyId, lockableCancelPolicyId(performance))) {
			throw new BusinessException(CommonExceptionCode.LOCK_ACQUISITION_FAILED);
		}
		validatePublishable(performance, now);

		List<PerformanceSchedule> schedules = performanceScheduleRepository
			.findByPerformanceIdAndStatusNotOrderByStartAt(performanceId, ScheduleStatus.CANCELED);
		validateSchedules(performance, schedules);

		List<ZoneGrade> zoneGrades = zoneGradeRepository.findAllWithGradeByPerformanceId(performanceId);
		List<VenueZone> venueZones = performance.getVenue().getZones();
		validateZones(zoneGrades, venueZones);
		List<SellerPerformanceResponse.UnsoldZone> unsoldZones =
			zonesOf(zoneGrades, ZoneAssignmentStatus.INTENTIONALLY_UNSOLD, venueZones).stream()
				.map(zone -> new SellerPerformanceResponse.UnsoldZone(zone.getId(), zone.getName()))
				.toList();

		// 공개 두 번이나 공연 취소와 겹쳐 락이 듣지 않아도 작성 중일 때만 바뀐다. (0행이면 이미 다른 상태)
		if (performanceRepository.publishIfDraft(performanceId, now) == 0) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_NOT_DRAFT);
		}
		return SellerPerformanceResponse.Published.builder()
			.performanceId(performanceId)
			.status(PerformanceStatus.PUBLISHED)
			.intentionallyUnsoldZones(unsoldZones)
			.build();
	}

	/** 공연 한 행만 보고 판단하는 조건. 작성 중 → 판매 필드 → 판매 종료 → 판매 오픈 순이다. */
	private void validatePublishable(Performance performance, LocalDateTime now) {
		if (performance.getStatus() != PerformanceStatus.DRAFT) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_NOT_DRAFT);
		}
		if (performance.getSaleOpenAt() == null || performance.getSaleCloseAt() == null
			|| performance.getMaxPerPerson() == null || performance.getReceiveType() == null
			|| performance.getBookingFee() == null) {
			throw new BusinessException(PerformanceExceptionCode.SALE_FIELDS_MISSING);
		}
		if (!performance.getSaleCloseAt().isAfter(now)) {
			throw new BusinessException(PerformanceExceptionCode.SALE_CLOSED);
		}
		if (!performance.getSaleOpenAt().isAfter(now.plus(PUBLISH_LEAD_TIME))) {
			throw new BusinessException(PerformanceExceptionCode.SALE_OPEN_TOO_SOON);
		}
	}

	/**
	 * 취소되지 않은 회차(공연 일시가 빠른 순)와 판매 기간의 관계. 회차가 있어야 하고, 모든 회차가 판매 오픈보다 늦게 시작해야 하며,
	 * 판매 종료가 마지막 회차 시작보다 늦으면 안 된다. 판매 종료 뒤에 공연하는 회차는 미리 파는 것이라 막지 않는다.
	 */
	private void validateSchedules(Performance performance, List<PerformanceSchedule> schedules) {
		if (schedules.isEmpty()) {
			throw new BusinessException(PerformanceExceptionCode.NO_ACTIVE_SCHEDULE);
		}
		List<String> tooEarly = schedules.stream()
			.map(PerformanceSchedule::getStartAt)
			.filter(startAt -> !startAt.isAfter(performance.getSaleOpenAt()))
			.map(SCHEDULE_TIME_FORMAT::format)
			.toList();
		if (!tooEarly.isEmpty()) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_BEFORE_SALE_OPEN,
				PerformanceExceptionCode.SCHEDULE_BEFORE_SALE_OPEN.getMessage() + ": " + String.join(", ", tooEarly));
		}
		if (performance.getSaleCloseAt().isAfter(schedules.getLast().getStartAt())) {
			throw new BusinessException(PerformanceExceptionCode.SALE_CLOSE_AFTER_LAST_SCHEDULE);
		}
	}

	/**
	 * 구역 설정 조건. 설정하지 않은 구역이 없어야 하고, 판매할 구역이 하나 이상 있어야 하며, 전석과 다른 등급이 섞이면 안 된다.
	 * 문제 구역은 에러 응답에 값을 담는 틀이 없어서 메시지에 이름을 넣는다.
	 */
	private void validateZones(List<ZoneGrade> zoneGrades, List<VenueZone> venueZones) {
		List<VenueZone> unsetZones = zonesOf(zoneGrades, ZoneAssignmentStatus.UNSET, venueZones);
		if (!unsetZones.isEmpty()) {
			throw new BusinessException(PerformanceExceptionCode.ZONE_UNSET_REMAINS,
				PerformanceExceptionCode.ZONE_UNSET_REMAINS.getMessage() + ": "
					+ String.join(", ", unsetZones.stream().map(VenueZone::getName).toList()));
		}
		List<SeatGradeName> assignedNames = zoneGrades.stream()
			.filter(zoneGrade -> zoneGrade.getAssignmentStatus() == ZoneAssignmentStatus.ASSIGNED)
			.map(zoneGrade -> zoneGrade.getGrade().getName())
			.toList();
		if (assignedNames.isEmpty()) {
			throw new BusinessException(PerformanceExceptionCode.NO_ASSIGNED_ZONE);
		}
		if (SeatGradeName.mixesAll(assignedNames)) {
			throw new BusinessException(PerformanceExceptionCode.GRADE_ALL_MIXED);
		}
	}

	/**
	 * 주어진 상태의 구역 설정에 해당하는 공연장 구역을 공연장 구역 순서(sortOrder, 같으면 번호순)로 돌려준다.
	 * 구역 설정은 공연장 구역 번호로만 연결되어 있어서(논리 FK) 맞는 구역이 없으면 데이터가 깨진 것이다. 조용히 빼지 않고 서버 오류로 드러낸다.
	 */
	private List<VenueZone> zonesOf(List<ZoneGrade> zoneGrades, ZoneAssignmentStatus status, List<VenueZone> venueZones) {
		return zoneGrades.stream()
			.filter(zoneGrade -> zoneGrade.getAssignmentStatus() == status)
			.map(zoneGrade -> venueZones.stream()
				.filter(venueZone -> venueZone.getId().equals(zoneGrade.getZoneId()))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
					"구역 설정이 가리키는 공연장 구역이 없습니다. zoneId=" + zoneGrade.getZoneId())))
			.sorted(Comparator.comparing(VenueZone::getSortOrder).thenComparing(VenueZone::getId))
			.toList();
	}

	/** 락을 잡을 규정 번호. 서비스 기본 규정(사업체 번호가 없음)이면 null 이다. findLockableCancelPolicyId 와 같은 기준이다. */
	private Long lockableCancelPolicyId(Performance performance) {
		CancelPolicy cancelPolicy = performance.getCancelPolicy();
		return cancelPolicy.getBusinessId() == null ? null : cancelPolicy.getId();
	}

	/** 내 공연 엔티티. 남의 공연은 번호가 있는지 알려주지 않으려고 없는 공연과 같은 404 로 답한다. */
	private Performance getMineEntity(Long businessId, Long performanceId) {
		return performanceRepository.findByIdAndBusinessId(performanceId, businessId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND));
	}

	/**
	 * 공연을 작성 중(DRAFT)으로 등록하고 상세 이미지를 저장한다.
	 * 공연장·취소 규정은 호출하는 쪽(파사드)이 각 서비스로 확인한 값을 받는다. 판매 필드는 DRAFT 에서 비워 둘 수 있다.
	 */
	@Transactional
	public Performance create(Long businessId, Long createdBy, Long venueId, CancelPolicy cancelPolicy,
		PerformanceSaveCommand command) {
		validateSalePeriod(command.saleOpenAt(), command.saleCloseAt());
		validateImageOrders(command.images());

		Performance performance = performanceRepository.save(Performance.builder()
			.businessId(businessId)
			.venueId(venueId)
			.createdBy(createdBy)
			.cancelPolicy(cancelPolicy)
			.title(command.title())
			.genre(command.genre())
			.ageLimit(command.ageLimit())
			.runningTime(command.runningTime())
			.castInfo(command.castInfo())
			.posterUrl(command.posterUrl())
			.notice(command.notice())
			.guide(command.guide())
			.status(PerformanceStatus.DRAFT)
			.saleOpenAt(command.saleOpenAt())
			.saleCloseAt(command.saleCloseAt())
			.maxPerPerson(command.maxPerPerson())
			.receiveType(command.receiveType())
			.bookingFee(command.bookingFee())
			.build());

		performanceImageRepository.saveAll(command.images().stream()
			.map(image -> PerformanceImage.builder()
				.performance(performance)
				.url(image.url())
				.sortOrder(image.sortOrder())
				.build())
			.toList());
		return performance;
	}

	/**
	 * 둘 다 있을 때만 순서를 본다. 하나만 있는 것은 작성 중에 허용하고 공개(SLR-04)에서 막는다.
	 * 과거 시각인지는 공개 시점에 따져야 의미가 있어서 여기서 보지 않는다.
	 */
	private void validateSalePeriod(LocalDateTime saleOpenAt, LocalDateTime saleCloseAt) {
		if (saleOpenAt != null && saleCloseAt != null && !saleCloseAt.isAfter(saleOpenAt)) {
			throw new BusinessException(PerformanceExceptionCode.SALE_PERIOD_INVALID);
		}
	}

	/** 같은 순서값이 둘이면 보여줄 순서가 정해지지 않는다. 목록 안의 관계라 요청 DTO 어노테이션으로는 막기 어렵다. */
	private void validateImageOrders(List<PerformanceSaveCommand.Image> images) {
		long distinctOrders = images.stream().map(PerformanceSaveCommand.Image::sortOrder).distinct().count();
		if (distinctOrders != images.size()) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT, "이미지 순서(sortOrder)가 중복되었습니다.");
		}
	}
}
