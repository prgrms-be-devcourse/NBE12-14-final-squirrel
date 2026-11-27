package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.dto.SellerPerformanceRequest;
import com.squirrel.tickting.domain.performance.dto.SellerPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceImageRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ScheduleSeatRepository;
import com.squirrel.tickting.domain.performance.infra.repository.ZoneGradeRepository;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.SeatGradeName;
import com.squirrel.tickting.domain.performance.type.SeatStatus;
import com.squirrel.tickting.domain.performance.type.ZoneAssignmentStatus;
import com.squirrel.tickting.domain.venue.infra.entity.VenueZone;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.ExceptionCode;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.exception.code.VenueExceptionCode;
import com.squirrel.tickting.global.lock.DistributedLockExecutor;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class SellerPerformanceService {

	/**
	 * 판매 오픈까지 이만큼 남아야 공개할 수 있다. (임시)
	 * 판매 좌석은 오픈 24시간 전부터 스케줄러가 1시간마다 만든다. 공개 뒤 오픈 전에 스케줄러가 최소 두 번 돌아
	 * 첫 생성이 실패해도 다시 만들 수 있게 한 값이다. 스케줄러 주기가 바뀌면 이 값도 다시 정한다.
	 * 작성 중 공연에 추가하거나 일시를 바꾸는 회차도 이보다 뒤여야 한다. 더 이른 회차가 있으면 공개 조건을 채울 수 없다.
	 */
	private static final Duration PUBLISH_LEAD_TIME = Duration.ofHours(3);
	/**
	 * 회차가 끝난 뒤 같은 공연장의 다음 회차까지 비워 두는 시간. 관객 입·퇴장과 무대 정리용이다. (임시, 팀과 상의해 정함)
	 * 하루 두 번 하는 공연이 막히지 않게 2시간 대신 1시간으로 했다. 실제 공연장의 기준은 확인하지 않았다.
	 */
	private static final Duration SCHEDULE_BUFFER = Duration.ofHours(1);
	/**
	 * 판매 오픈까지 남은 시간이 이 이하인 공개 공연은 판매 좌석을 만들 때가 된 것이다. (임시)
	 * 자동 생성(스케줄러)과 회차를 추가할 때의 즉시 생성이 같이 쓴다.
	 */
	private static final Duration SEAT_GENERATION_LEAD_TIME = Duration.ofHours(24);
	/**
	 * 자동 생성에서 실패로 보지 않는 코드. 그 사이 만들어졌거나 대상에서 빠졌거나, 다른 작업이 락을 쥐고 있어 다음 실행에서 다시 만든다.
	 */
	private static final Set<ExceptionCode> SKIPPED_SEAT_GENERATION_CODES = Set.of(
		PerformanceExceptionCode.SEATS_ALREADY_GENERATED, PerformanceExceptionCode.PERFORMANCE_CANCELED,
		PerformanceExceptionCode.SCHEDULE_CANCELED, PerformanceExceptionCode.SCHEDULE_ALREADY_STARTED,
		CommonExceptionCode.LOCK_ACQUISITION_FAILED);
	/** 일시를 바꿀 수 있는 회차 상태. 취소·연기 회차는 막는다. */
	private static final Set<ScheduleStatus> RESCHEDULABLE_STATUSES = EnumSet.of(ScheduleStatus.ON_SALE,
		ScheduleStatus.PAUSED);
	private static final DateTimeFormatter SCHEDULE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final DateTimeFormatter TIME_ONLY_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

	private final PerformanceRepository performanceRepository;
	private final PerformanceImageRepository performanceImageRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;
	private final ZoneGradeRepository zoneGradeRepository;
	private final ScheduleSeatRepository scheduleSeatRepository;
	private final DistributedLockExecutor lockExecutor;
	private final TransactionTemplate transactionTemplate;
	private final PerformanceProperties properties;

	/**
	 * 내 사업체 공연의 회차인지 확인한다. 아니면 없는 회차와 같은 404 로 답한다.
	 * 취소·연기된 회차, 취소·작성 중 공연의 회차도 막지 않는다. (읽기 전용 화면이 이력을 봐야 해서)
	 */
	@Transactional(readOnly = true)
	public void validateMySchedule(Long businessId, Long scheduleId) {
		if (!performanceScheduleRepository.existsByIdAndPerformanceBusinessId(scheduleId, businessId)) {
			throw new BusinessException(PerformanceExceptionCode.SELLER_SCHEDULE_NOT_FOUND);
		}
	}

	/**
	 * 내 사업체의 공연 목록. 상태를 주지 않으면 취소된 공연까지 모든 상태가 나온다.
	 * 공연장명과 회차 수는 공연의 읽기 전용 연관(공연장, 회차)에서 꺼낸다. 지연 로딩이라 응답을 이 트랜잭션 안에서 만든다.
	 */
	@Transactional(readOnly = true)
	public Page<SellerPerformanceResponse.Summary> searchMine(Long businessId, PerformanceStatus status, Pageable pageable) {
		var statuses = status == null ? EnumSet.allOf(PerformanceStatus.class) : EnumSet.of(status);
		return performanceRepository.search(businessId, statuses, pageable).map(SellerPerformanceResponse.Summary::from);
	}

	/** 공연의 상세 이미지(순서대로). 감사 로그에 바뀌기 전후 값을 남길 때 쓴다. */
	@Transactional(readOnly = true)
	public List<SellerPerformanceRequest.Image> getImages(Long performanceId) {
		return performanceImageRepository.findByPerformanceIdOrderBySortOrder(performanceId).stream()
			.map(image -> new SellerPerformanceRequest.Image(image.getUrl(), image.getSortOrder()))
			.toList();
	}

	/** 내 사업체의 공연 상세. 남의 공연은 없는 공연과 같은 404로 답한다. */
	@Transactional(readOnly = true)
	public SellerPerformanceResponse.Detail getMine(Long businessId, Long performanceId) {
		Performance performance = getMineEntity(businessId, performanceId);
		return SellerPerformanceResponse.Detail.from(performance,
			performanceImageRepository.findByPerformanceIdOrderBySortOrder(performanceId),
			performanceScheduleRepository.findByPerformanceIdOrderByStartAt(performanceId));
	}

	/**
	 * 내 공연의 회차별 판매 현황. 취소·연기·중지 회차도 공연 일시순으로 모두 준다. 읽기만 해서 락을 잡지 않는다.
	 * 총 좌석은 좌석 생성 여부와 상관없이 공연장 좌석 수(공연의 읽기 전용 연관)다. 응답을 이 트랜잭션 안에서 만든다.
	 * 취소·작성 중 공연도 막지 않는다. 취소 뒤 환불이 끝났는지 볼 수 있어야 해서다.
	 */
	@Transactional(readOnly = true)
	public SellerPerformanceResponse.Sales getSales(Long businessId, Long performanceId) {
		Performance performance = getMineEntity(businessId, performanceId);
		List<PerformanceSchedule> schedules = performanceScheduleRepository.findByPerformanceIdOrderByStartAt(performanceId);
		List<Long> scheduleIds = schedules.stream().map(PerformanceSchedule::getId).toList();
		// 회차가 없으면 IN () 쿼리를 보내지 않는다.
		List<ScheduleSeatRepository.SeatStatusCount> counts = scheduleIds.isEmpty()
			? List.of() : scheduleSeatRepository.countByStatus(scheduleIds);
		Integer totalSeats = performance.getVenue().getTotalSeats();

		List<SellerPerformanceResponse.ScheduleSales> response = schedules.stream()
			.map(schedule -> SellerPerformanceResponse.ScheduleSales.of(schedule, totalSeats,
				counts.stream().filter(c -> c.getScheduleId().equals(schedule.getId())).toList()))
			.toList();
		return new SellerPerformanceResponse.Sales(response);
	}

	/**
	 * 공개할 때 함께 잡을 취소 규정 번호. 서비스 기본 규정이면 null 이다.
	 * 남의 공연은 락을 잡기 전에 404 로 끝내서 남의 공연 상태를 드러내지 않는다.
	 */
	public Long getPublishLockPolicyId(Long businessId, Long performanceId) {
		getMineEntity(businessId, performanceId);
		return performanceRepository.findLockableCancelPolicyId(performanceId).orElse(null);
	}

	/**
	 * 작성 중인 내 공연을 공개(PUBLISHED)한다. 공개하면 구역·등급·취소 규정을 되돌릴 수 없어서 조건을 모두 만족해야 한다.
	 * 호출한 쪽(파사드)이 공연 락과 규정 락을 잡고 트랜잭션을 연 상태에서 부른다.
	 * 락을 기다리는 사이 상태가 바뀌었을 수 있어 락 밖의 판단은 믿지 않고 다시 읽는다.
	 * 검사 순서: 공연 한 행 → 회차 → 구역 설정. 앞에서 막히면 뒤의 쿼리는 나가지 않는다.
	 * 회차를 구역보다 먼저 두는 것은 읽을 행 수가 적을 것이라는 추정이고 측정하지는 않았다.
	 */
	@Transactional
	public SellerPerformanceResponse.Published publish(Long businessId, Long performanceId,
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
		if (schedules.isEmpty()) {
			throw new BusinessException(PerformanceExceptionCode.NO_ACTIVE_SCHEDULE);
		}
		validateSaleWindow(performance.getSaleOpenAt(), performance.getSaleCloseAt(), schedules);

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

	/**
	 * 내 공연에 회차를 하나 추가한다. 공개 공연이고 판매 오픈이 가까우면 같은 트랜잭션에서 판매 좌석까지 만들고,
	 * 좌석을 만들 수 없으면 회차도 남기지 않는다. (회차만 남으면 판매자는 성공으로 알고 예매자에게는 매진으로 보인다)
	 * 호출한 쪽(파사드)이 공연 락을 잡고 트랜잭션을 연 상태에서 부른다.
	 * 검사 순서: 공연 한 행(취소) → 공연장 → 새 회차의 시각 → (공개) 기존 회차 → 판매 종료 → 일시 중지 → (즉시 생성) 구역 설정 → 같은 공연장 겹침.
	 * 앞에서 막히면 뒤의 쿼리는 나가지 않는다. 처리량은 추정이고 측정하지 않았다.
	 * 지난 시각은 요청 DTO 가 막으므로 여기서 다시 보지 않는다.
	 */
	@Transactional
	public SellerPerformanceResponse.ScheduleCreated addSchedule(Long businessId, Long performanceId,
		LocalDateTime startAt) {
		// 현재 시각은 한 번만 읽어 모든 비교·UPDATE·응답에 같은 값을 쓴다.
		LocalDateTime now = LocalDateTime.now();
		Performance performance = getMineEntity(businessId, performanceId);
		boolean published = performance.getStatus() == PerformanceStatus.PUBLISHED;

		if (performance.getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED, "취소된 공연에는 회차를 추가할 수 없습니다.");
		}
		// 사용 중지된 공연장에 회차를 더 팔면 환불해야 할 수 있다. 등록·공연장 변경과 같은 기준이다.
		if (performance.getVenue().getStatus() != VenueStatus.ACTIVE) {
			throw new BusinessException(VenueExceptionCode.VENUE_INACTIVE);
		}
		validateNewStartAt(performance, startAt, now, PerformanceExceptionCode.DRAFT_SCHEDULE_TOO_SOON.getMessage());
		if (published) {
			validateStillOnSale(performance, now);
		}
		// 작성 중 공연은 좌석을 만들면 공개 전에 등급·가격·구역이 잠기므로 만들지 않는다.
		boolean generatesSeats = isSeatGenerationDue(performance, now);
		if (generatesSeats) {
			validateZonesForSeats(zoneGradeRepository.findAllWithGradeByPerformanceId(performanceId),
				performance.getVenue().getZones(),
				"판매 좌석을 바로 만들어야 해서 회차를 추가할 수 없습니다. 설정하지 않은 구역이 있습니다",
				"판매 좌석을 바로 만들어야 해서 회차를 추가할 수 없습니다. 판매할 구역이 하나 이상 있어야 합니다.");
		}
		validateNewScheduleNoOverlap(businessId, performance, startAt, null);

		PerformanceSchedule schedule = performanceScheduleRepository.save(PerformanceSchedule.builder()
			.performance(performance)
			.startAt(startAt)
			.status(ScheduleStatus.ON_SALE)
			.build());
		if (generatesSeats) {
			createSeats(schedule.getId(), performance, now);
		}
		return SellerPerformanceResponse.ScheduleCreated.from(schedule, generatesSeats ? now : null);
	}

	/**
	 * 판매 오픈보다 늦게 시작해야 하고(오픈이 비어 있으면 보지 않는다), 작성 중 공연은 공개 준비 시간보다 뒤여야 한다.
	 * 회차 추가와 수정이 함께 쓰고, 공개 준비 시간에 걸린 메시지는 판매자가 하려던 일에 맞춰 호출한 쪽이 정한다.
	 * 판매 종료가 마지막 회차보다 늦은지는 보지 않는다. 회차를 하나씩 넣는 중에는 첫 회차부터 걸려서, 추가에서는 공개·공연 수정이 확인한다.
	 */
	private void validateNewStartAt(Performance performance, LocalDateTime startAt, LocalDateTime now,
		String tooSoonMessage) {
		LocalDateTime saleOpenAt = performance.getSaleOpenAt();
		if (saleOpenAt != null && !startAt.isAfter(saleOpenAt)) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_BEFORE_SALE_OPEN,
				"회차는 판매 오픈 일시(" + SCHEDULE_TIME_FORMAT.format(saleOpenAt) + ")보다 늦게 시작해야 합니다.");
		}
		if (performance.getStatus() == PerformanceStatus.DRAFT && !startAt.isAfter(now.plus(PUBLISH_LEAD_TIME))) {
			throw new BusinessException(PerformanceExceptionCode.DRAFT_SCHEDULE_TOO_SOON, tooSoonMessage);
		}
	}

	/**
	 * 공개된 공연에 회차를 더할 수 있는 상태인가. 취소되지 않은 회차가 모두 시작했으면 끝난 공연이고, 판매가 끝났으면 새 회차를 팔 수 없다.
	 * 둘 다면 앞의 것을 알린다. 끝난 공연은 판매 종료를 늘릴 수 없어서 안내가 달라야 한다.
	 * 아직 시작하지 않은 회차를 모두 일시 중지한 동안에도 더할 수 없다. 멈춘 공연이 새 회차로 다시 팔리지 않게 한다.
	 */
	private void validateStillOnSale(Performance performance, LocalDateTime now) {
		List<PerformanceSchedule> schedules = performanceScheduleRepository
			.findByPerformanceIdAndStatusNotOrderByStartAt(performance.getId(), ScheduleStatus.CANCELED);
		if (!schedules.isEmpty() && !schedules.getLast().getStartAt().isAfter(now)) {
			throw new BusinessException(PerformanceExceptionCode.ALL_SCHEDULES_STARTED);
		}
		if (!performance.getSaleCloseAt().isAfter(now)) {
			throw new BusinessException(PerformanceExceptionCode.SALE_CLOSED,
				"판매가 끝난 공연에는 회차를 추가할 수 없습니다. 판매 종료 일시를 먼저 늘려 주세요.");
		}
		if (isEveryUpcomingSchedulePaused(schedules, now)) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_ADD_WHILE_PAUSED);
		}
	}

	/** 아직 시작하지 않은 판매 중·일시 중지 회차가 하나 이상 있고 모두 일시 중지인가. 시작했거나 연기된 회차는 보지 않는다. */
	private boolean isEveryUpcomingSchedulePaused(List<PerformanceSchedule> schedules, LocalDateTime now) {
		List<ScheduleStatus> upcoming = schedules.stream()
			.filter(schedule -> schedule.getStartAt().isAfter(now))
			.map(PerformanceSchedule::getStatus)
			.filter(status -> status == ScheduleStatus.ON_SALE || status == ScheduleStatus.PAUSED)
			.toList();
		return !upcoming.isEmpty() && upcoming.stream().allMatch(status -> status == ScheduleStatus.PAUSED);
	}

	/** 중지·재개 대상을 고르는 방식. DB 를 보지 않는 검사라 조회보다 먼저 한다. all 이 false 면 보내지 않은 것과 같다. */
	public void validateScheduleSelection(Boolean all, List<Long> scheduleIds) {
		if (Boolean.TRUE.equals(all) == (scheduleIds != null)) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT, "all: true와 scheduleIds 중 하나만 보내 주세요.");
		}
	}

	/** 고른 회차(scheduleIds 가 null 이면 모두)의 판매를 일시 중지한다. 호출 조건과 검사 순서는 changeScheduleSale 과 같다. */
	@Transactional
	public ScheduleSaleChange pauseSchedules(Long businessId, Long performanceId,
		SellerPerformanceRequest.SchedulePause request) {
		return changeScheduleSale(businessId, performanceId, request.scheduleIds(), ScheduleStatus.ON_SALE,
			ScheduleStatus.PAUSED, request.reason(), PerformanceExceptionCode.NO_SCHEDULE_TO_PAUSE);
	}

	/** 일시 중지한 회차를 다시 판매 중으로 바꾸고 중지 사유를 비운다. "모두"는 따로 중지한 회차까지 모두다. */
	@Transactional
	public ScheduleSaleChange resumeSchedules(Long businessId, Long performanceId,
		SellerPerformanceRequest.ScheduleResume request) {
		return changeScheduleSale(businessId, performanceId, request.scheduleIds(), ScheduleStatus.PAUSED,
			ScheduleStatus.ON_SALE, null, PerformanceExceptionCode.NO_SCHEDULE_TO_RESUME);
	}

	/**
	 * 상태가 from 이고 아직 시작하지 않은 회차만 to 로 바꾸고, 나머지는 이유와 함께 건너뛴다.
	 * 호출한 쪽(파사드)이 공연 락을 잡고 트랜잭션을 연 상태에서 부른다.
	 * 검사 순서: 공연 한 행(취소 → 작성 중) → 이 공연의 회차 목록(남의 번호 404 → 바꿀 것 없음) → 조건부 UPDATE.
	 * 바뀐 행이 대상보다 적으면 락이 듣지 않아 그 사이 바뀐 것이라 전체를 되돌리고 COMMON004 로 답한다.
	 */
	private ScheduleSaleChange changeScheduleSale(Long businessId, Long performanceId, List<Long> scheduleIds,
		ScheduleStatus from, ScheduleStatus to, String reason, ExceptionCode noTargetCode) {
		// 현재 시각은 한 번만 읽어 대상 분류와 UPDATE 에 같은 값을 쓴다.
		LocalDateTime now = LocalDateTime.now();
		Performance performance = getMineEntity(businessId, performanceId);
		if (performance.getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED,
				"취소된 공연의 회차는 중지하거나 재개할 수 없습니다.");
		}
		if (performance.getStatus() == PerformanceStatus.DRAFT) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_SALE_DRAFT_PERFORMANCE);
		}

		List<PerformanceSchedule> schedules = performanceScheduleRepository.findByPerformanceIdOrderByStartAt(performanceId)
			.stream()
			.sorted(Comparator.comparing(PerformanceSchedule::getStartAt).thenComparing(PerformanceSchedule::getId))
			.toList();
		if (scheduleIds != null) {
			Set<Long> requested = new HashSet<>(scheduleIds);
			schedules = schedules.stream().filter(schedule -> requested.contains(schedule.getId())).toList();
			if (schedules.size() != requested.size()) {
				throw new BusinessException(PerformanceExceptionCode.SELLER_SCHEDULE_NOT_FOUND);
			}
		}

		List<PerformanceSchedule> changed = new ArrayList<>();
		List<SellerPerformanceResponse.SkippedSchedule> skipped = new ArrayList<>();
		for (PerformanceSchedule schedule : schedules) {
			SellerPerformanceResponse.SkipReason skipReason = findSkipReason(schedule, from, now);
			if (skipReason == null) {
				changed.add(schedule);
			} else {
				skipped.add(new SellerPerformanceResponse.SkippedSchedule(schedule.getId(), skipReason));
			}
		}
		if (changed.isEmpty()) {
			throw new BusinessException(noTargetCode);
		}

		List<Long> changedIds = changed.stream().map(PerformanceSchedule::getId).toList();
		if (performanceScheduleRepository.updateSaleStatus(changedIds, from, to, reason, now) != changedIds.size()) {
			throw new BusinessException(CommonExceptionCode.LOCK_ACQUISITION_FAILED);
		}
		return new ScheduleSaleChange(
			new SellerPerformanceResponse.ScheduleSaleChanged(performanceId, changedIds, skipped), changed);
	}

	/** 바꾸지 않는 이유. 바꿀 수 있으면 null 이다. */
	private SellerPerformanceResponse.SkipReason findSkipReason(PerformanceSchedule schedule, ScheduleStatus from,
		LocalDateTime now) {
		if (schedule.getStatus() == ScheduleStatus.CANCELED) {
			return SellerPerformanceResponse.SkipReason.CANCELED;
		}
		if (schedule.getStatus() == ScheduleStatus.POSTPONED) {
			return SellerPerformanceResponse.SkipReason.POSTPONED;
		}
		if (!schedule.getStartAt().isAfter(now)) {
			return SellerPerformanceResponse.SkipReason.STARTED;
		}
		if (schedule.getStatus() == from) {
			return null;
		}
		return from == ScheduleStatus.ON_SALE ? SellerPerformanceResponse.SkipReason.ALREADY_PAUSED
			: SellerPerformanceResponse.SkipReason.ALREADY_ON_SALE;
	}

	/**
	 * 새 회차나 일시를 바꾸는 회차가 같은 공연장의 다른 회차와 겹치지 않는지 본다. 내 사업체 회차와 겹치면 입력 실수를 바로 고칠 수 있게
	 * 공연명·일시·사용 종료 시각을 알려 주고, 다른 판매자의 회차는 미발표 일정일 수 있어 알려 주지 않는다.
	 * excludedScheduleId 는 일시를 바꾸는 회차 자신이고, 새 회차면 null 이다.
	 */
	private void validateNewScheduleNoOverlap(Long businessId, Performance performance, LocalDateTime startAt,
		Long excludedScheduleId) {
		List<PerformanceScheduleRepository.OverlappingSchedule> overlapped = performanceScheduleRepository
			.findOverlappingSchedules(performance.getVenueId(), startAt, performance.getRunningTime(),
				(int) SCHEDULE_BUFFER.toMinutes(), excludedScheduleId);
		if (overlapped.isEmpty()) {
			return;
		}
		List<String> mine = overlapped.stream()
			.filter(schedule -> schedule.getBusinessId().equals(businessId))
			.map(this::describeOccupation)
			.toList();
		String message = mine.isEmpty() ? PerformanceExceptionCode.SCHEDULE_TIME_OVERLAP.getMessage()
			: "같은 공연장의 다른 회차와 시간이 겹칩니다: " + String.join(", ", mine);
		throw new BusinessException(PerformanceExceptionCode.SCHEDULE_TIME_OVERLAP, message);
	}

	/** "공연명 2026-12-20 18:00 (21:30까지 사용)". 끝나는 날이 다르면 날짜까지 적는다. */
	private String describeOccupation(PerformanceScheduleRepository.OverlappingSchedule schedule) {
		LocalDateTime startAt = schedule.getStartAt();
		LocalDateTime endAt = startAt.plusMinutes(schedule.getRunningTime()).plus(SCHEDULE_BUFFER);
		DateTimeFormatter endFormat = endAt.toLocalDate().equals(startAt.toLocalDate())
			? TIME_ONLY_FORMAT : SCHEDULE_TIME_FORMAT;
		return schedule.getTitle() + " " + SCHEDULE_TIME_FORMAT.format(startAt) + " (" + endFormat.format(endAt) + "까지 사용)";
	}

	/**
	 * 내 사업체의 회차를 공연과 함께 읽는다. 남의 회차는 없는 회차와 같은 404 다. 호출한 쪽의 트랜잭션에 참여하고,
	 * 락 밖에서 부르면 공연 번호만 믿는다.
	 */
	@Transactional(readOnly = true)
	public PerformanceSchedule getMySchedule(Long businessId, Long scheduleId) {
		return performanceScheduleRepository.findWithPerformanceById(scheduleId)
			.filter(found -> found.getPerformance().getBusinessId().equals(businessId))
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.SELLER_SCHEDULE_NOT_FOUND));
	}

	/**
	 * 일시를 바꿀 수 있는 회차인가. 순서: 취소된 공연 → 취소 회차 → 연기 회차 → 이미 시작함. 모두 이미 읽은 값으로 판단한다.
	 * 시작한 회차를 뒤로 옮기면 지난 공연이 다시 팔리고, 끝난 공연의 회차 추가 거절을 피해 갈 수 있어 막는다.
	 */
	public void validateReschedulable(PerformanceSchedule schedule, LocalDateTime now) {
		if (schedule.getPerformance().getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED);
		}
		if (schedule.getStatus() == ScheduleStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_CANCELED, "취소된 회차는 일시를 바꿀 수 없습니다.");
		}
		if (schedule.getStatus() == ScheduleStatus.POSTPONED) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_POSTPONED);
		}
		if (!schedule.getStartAt().isAfter(now)) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_ALREADY_STARTED, "이미 시작한 회차는 일시를 바꿀 수 없습니다.");
		}
	}

	/** 바꿀 일시가 회차 추가와 같은 조건을 지키는가. 지난 시각은 요청 DTO 가 막는다. */
	public void validateRescheduleStartAt(Performance performance, LocalDateTime startAt, LocalDateTime now) {
		validateNewStartAt(performance, startAt, now, "작성 중인 공연의 회차는 지금부터 3시간 뒤의 일시로만 바꿀 수 있습니다.");
	}

	/**
	 * 회차의 공연 일시를 바꾼다. 상태·판매 좌석은 그대로다. 회차 상태, 새 일시, 예매는 호출한 쪽이 락 안에서 먼저 확인한다.
	 * 검사 순서: (공개) 판매 종료 → 같은 공연장 겹침 → 조건부 UPDATE. 이 공연의 회차 목록이 겹침 조회보다 읽을 것이 적을 것이라는
	 * 추정이고 측정하지 않았다. 0행이면 락이 듣지 않아 그 사이 바뀐 것이라, 다시 요청하면 정확한 에러가 나오게 COMMON004 로 답한다.
	 */
	@Transactional
	public SellerPerformanceResponse.ScheduleUpdated reschedule(Long businessId, PerformanceSchedule schedule,
		LocalDateTime startAt, LocalDateTime now) {
		Performance performance = schedule.getPerformance();
		if (performance.getStatus() == PerformanceStatus.PUBLISHED) {
			validateSaleCloseWithin(performance, schedule.getId(), startAt);
		}
		validateNewScheduleNoOverlap(businessId, performance, startAt, schedule.getId());

		if (performanceScheduleRepository.updateStartAt(schedule.getId(), startAt, RESCHEDULABLE_STATUSES, now) == 0) {
			throw new BusinessException(CommonExceptionCode.LOCK_ACQUISITION_FAILED);
		}
		// 일괄 UPDATE 는 엔티티에 반영되지 않아 저장한 값으로 응답한다.
		return scheduleUpdatedResponse(schedule.getId(), startAt, now);
	}

	/** 바꾼 것이 없을 때의 응답. 저장하지 않았으므로 수정 일시는 이전 값이다. */
	public SellerPerformanceResponse.ScheduleUpdated unchangedScheduleResponse(PerformanceSchedule schedule) {
		return scheduleUpdatedResponse(schedule.getId(), schedule.getStartAt(), schedule.getUpdatedAt());
	}

	private SellerPerformanceResponse.ScheduleUpdated scheduleUpdatedResponse(Long scheduleId, LocalDateTime startAt,
		LocalDateTime updatedAt) {
		return SellerPerformanceResponse.ScheduleUpdated.builder()
			.scheduleId(scheduleId)
			.startAt(startAt)
			.updatedAt(updatedAt)
			.build();
	}

	/**
	 * 회차를 옮긴 뒤에도 판매 종료가 마지막 회차 시작보다 늦지 않은가. 옮기는 회차는 새 일시로 보고 취소 회차는 빼고 본다.
	 * 어긋난 채 저장하면 이후의 공연 수정이 제목만 고쳐도 막혀서 여기서 거절한다.
	 * 다른 회차가 판매 오픈보다 늦은지는 보지 않는다. 이번에 바꾸는 값과 상관없는 회차 때문에 막히지 않게 하려는 것이다.
	 */
	private void validateSaleCloseWithin(Performance performance, Long movedScheduleId, LocalDateTime newStartAt) {
		LocalDateTime lastStartAt = performanceScheduleRepository
			.findByPerformanceIdAndStatusNotOrderByStartAt(performance.getId(), ScheduleStatus.CANCELED)
			.stream()
			.filter(other -> !other.getId().equals(movedScheduleId))
			.map(PerformanceSchedule::getStartAt)
			.reduce(newStartAt, (last, startAt) -> startAt.isAfter(last) ? startAt : last);
		if (performance.getSaleCloseAt().isAfter(lastStartAt)) {
			throw new BusinessException(PerformanceExceptionCode.SALE_CLOSE_AFTER_LAST_SCHEDULE,
				PerformanceExceptionCode.SALE_CLOSE_AFTER_LAST_SCHEDULE.getMessage() + " 판매 종료 일시를 "
					+ SCHEDULE_TIME_FORMAT.format(lastStartAt) + " 이전으로 먼저 앞당겨 주세요.");
		}
	}

	/**
	 * 판매자가 "좌석 지금 생성"을 눌러 한 회차의 판매 좌석을 만든다. 만든 순간부터 그 공연의 등급·가격·구역을 고칠 수 없고 되돌릴 수 없다.
	 * 남의 회차는 락을 잡기 전에 404 로 끝내서 남의 회차 상태를 드러내지 않는다.
	 * 락은 커밋 뒤에 풀려야 해서 이 메서드에는 @Transactional 을 붙이지 않는다.
	 */
	public SellerPerformanceResponse.SeatsGenerated generateSeats(Long businessId, Long scheduleId) {
		PerformanceSchedule schedule = getMySchedule(businessId, scheduleId);
		return inPerformanceLock(schedule.getPerformance().getId(), () -> {
			// 현재 시각은 한 번만 읽어 모든 비교·UPDATE·응답에 같은 값을 쓴다.
			LocalDateTime now = LocalDateTime.now();
			return generateSeatsInTransaction(getScheduleWithPerformance(scheduleId), now);
		});
	}

	/**
	 * 판매 좌석을 만들 때가 된 회차를 모두 만든다. (스케줄러가 부른다) 회차 하나가 락 하나·트랜잭션 하나라,
	 * 한 회차가 실패해도 만든 것은 남고 나머지를 계속 만든다. 실패한 회차는 대상에 남아 다음 실행에서 다시 시도한다.
	 * 락 실행기가 트랜잭션 안을 거부해서 @Transactional 을 붙이지 않는다. 대상 조회가 실패하면 예외를 그대로 던진다.
	 */
	public void generateDueSeats() {
		LocalDateTime now = LocalDateTime.now();
		performanceScheduleRepository.findSeatGenerationTargets(PerformanceStatus.PUBLISHED, ScheduleStatus.CANCELED,
				now, now.plus(SEAT_GENERATION_LEAD_TIME))
			.forEach(target -> generateDueSeatsOf(target.getPerformanceId(), target.getScheduleId()));
	}

	/** 한 회차를 자동으로 만들고 결과를 로그로 남긴다. 다음 회차를 계속 만들 수 있게 예외를 밖으로 던지지 않는다. */
	private void generateDueSeatsOf(Long performanceId, Long scheduleId) {
		try {
			generateSeatsBySystem(performanceId, scheduleId).ifPresentOrElse(
				generated -> log.info("판매 좌석을 자동으로 만들었다. 공연 {}, 회차 {}, 좌석 {}개", performanceId, scheduleId,
					generated.totalSeats()),
				() -> log.debug("자동 생성 대상에서 빠져 건너뛴다. 공연 {}, 회차 {}", performanceId, scheduleId));
		} catch (BusinessException e) {
			if (SKIPPED_SEAT_GENERATION_CODES.contains(e.getExceptionCode())) {
				log.debug("판매 좌석 자동 생성을 건너뛴다. 공연 {}, 회차 {}, 코드 {}", performanceId, scheduleId,
					e.getExceptionCode().getCode());
			} else {
				log.warn("판매 좌석을 자동으로 만들지 못했다. 공연 {}, 회차 {}, 코드 {}: {}", performanceId, scheduleId,
					e.getExceptionCode().getCode(), e.getMessage());
			}
		} catch (RuntimeException e) {
			log.error("판매 좌석 자동 생성 중 오류가 났다. 공연 {}, 회차 {}", performanceId, scheduleId, e);
		}
	}

	/**
	 * 자동 생성 전용으로, 소유 확인 없이 한 회차의 판매 좌석을 만든다. 만들 때가 된 회차가 아니면 만들지 않고 빈 값을 돌려준다.
	 * 대상을 읽은 뒤 락을 잡기 전에 판매 오픈이 뒤로 바뀔 수 있고, 좌석을 일찍 만들면 등급·가격·구역이 되돌릴 수 없이 잠기므로
	 * 락 안에서 읽은 값으로 다시 확인한다. 취소·생성됨·시작함은 버튼과 같은 검사가 막는다. 락을 못 잡으면 COMMON004 다.
	 */
	public Optional<SellerPerformanceResponse.SeatsGenerated> generateSeatsBySystem(Long performanceId,
		Long scheduleId) {
		return inPerformanceLock(performanceId, () -> {
			LocalDateTime now = LocalDateTime.now();
			PerformanceSchedule schedule = getScheduleWithPerformance(scheduleId);
			if (!isSeatGenerationDue(schedule.getPerformance(), now)) {
				return Optional.empty();
			}
			return Optional.of(generateSeatsInTransaction(schedule, now));
		});
	}

	/**
	 * 공연 락을 잡고 그 안에서 트랜잭션을 연다. 락 performance:{공연 번호} 는 등급·구역 수정(SLR-10·11)·공개(SLR-04)와 같은 이름이라,
	 * 가격·구역을 복사하는 것과 그 값을 고치는 것이 서로 막는다.
	 */
	private <T> T inPerformanceLock(Long performanceId, Supplier<T> action) {
		return lockExecutor.execute(List.of(PerformanceLockNames.performance(performanceId)), properties.lockWait(),
			() -> transactionTemplate.execute(status -> action.get()));
	}

	private PerformanceSchedule getScheduleWithPerformance(Long scheduleId) {
		return performanceScheduleRepository.findWithPerformanceById(scheduleId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.SELLER_SCHEDULE_NOT_FOUND));
	}

	/** 상태를 먼저 본다. 작성 중 공연은 판매 오픈이 비어 있을 수 있다. */
	private boolean isSeatGenerationDue(Performance performance, LocalDateTime now) {
		return performance.getStatus() == PerformanceStatus.PUBLISHED
			&& !now.isBefore(performance.getSaleOpenAt().minus(SEAT_GENERATION_LEAD_TIME));
	}

	/**
	 * 락과 트랜잭션이 이미 열려 있고 회차를 락 안에서 읽었다고 가정하고 검사·복사한다. 락을 기다리는 사이 상태가 바뀌었을 수 있어
	 * 락 밖에서 읽은 값은 쓰지 않는다.
	 * 검사 순서: 공연 상태 → 회차 상태 → 이미 생성됨 → 시작 시각 → 판매 정보 → 구역 설정 → (조건부 UPDATE, 복사).
	 * 앞의 것일수록 이미 읽은 행 하나로 판단하고, 구역 설정(작은 목록)을 거쳐 수천 행을 만드는 복사가 맨 끝이다. 처리량은 추정이고 측정하지 않았다.
	 */
	private SellerPerformanceResponse.SeatsGenerated generateSeatsInTransaction(PerformanceSchedule schedule,
		LocalDateTime now) {
		Performance performance = schedule.getPerformance();

		if (performance.getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED);
		}
		if (schedule.getStatus() == ScheduleStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_CANCELED);
		}
		if (schedule.getSeatsGeneratedAt() != null) {
			throw new BusinessException(PerformanceExceptionCode.SEATS_ALREADY_GENERATED);
		}
		if (!schedule.getStartAt().isAfter(now)) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_ALREADY_STARTED);
		}
		// 공개된 공연은 공개 조건이 판매 정보를 보장한다. 작성 중 공연만 비어 있을 수 있어, 빈 초안에서 실수로 눌러 가격이 잠기지 않게 한다.
		if (performance.getStatus() == PerformanceStatus.DRAFT && hasMissingSaleField(performance)) {
			throw new BusinessException(PerformanceExceptionCode.SALE_FIELDS_MISSING,
				"판매 오픈·종료 일시, 1인 최대 매수, 수령 방법, 예매 수수료를 모두 설정해야 판매 좌석을 만들 수 있습니다.");
		}

		List<ZoneGrade> zoneGrades = zoneGradeRepository.findAllWithGradeByPerformanceId(performance.getId());
		validateZonesForSeats(zoneGrades, performance.getVenue().getZones(),
			PerformanceExceptionCode.ZONE_UNSET_REMAINS.getMessage(), "판매할 구역이 하나 이상 있어야 판매 좌석을 만들 수 있습니다.");

		createSeats(schedule.getId(), performance, now);
		return seatsGeneratedResponse(schedule.getId(), now);
	}

	/** 생성 시각을 채우고 공연장 좌석을 복사한다. 락과 트랜잭션이 열려 있고 구역 조건을 확인한 뒤에 부른다. */
	private void createSeats(Long scheduleId, Performance performance, LocalDateTime now) {
		// 락이 듣지 않아도 한 회차는 한 번만 만든다. 0행이면 그 사이 만들어졌거나 취소됐다.
		if (performanceScheduleRepository.markSeatsGenerated(scheduleId, ScheduleStatus.CANCELED, now) == 0) {
			throw new BusinessException(PerformanceExceptionCode.SEATS_ALREADY_GENERATED);
		}
		int copied = scheduleSeatRepository.copyFromVenueSeats(scheduleId, performance.getId(), performance.getVenueId(), now);
		// 판매 구역이 있는데 복사할 공연장 좌석이 없는 깨진 데이터. 예외를 던지면 같은 트랜잭션이라 방금 채운 seats_generated_at 도 함께 되돌아간다.
		if (copied == 0) {
			throw new BusinessException(PerformanceExceptionCode.NO_VENUE_SEATS_TO_COPY);
		}
	}

	/**
	 * 좌석 생성 때의 구역 조건. 설정하지 않은(UNSET) 구역이 없어야 하고 판매할(ASSIGNED) 구역이 하나 이상 있어야 한다.
	 * 버튼으로 만들 때와 회차를 추가하며 만들 때 판매자가 하려던 일이 달라서 메시지는 호출한 쪽이 정한다. (UNSET 은 뒤에 구역 이름이 붙는다)
	 * 판매 구역이 0개면 전부 BLOCKED 인 회차가 만들어져 잠기고, 작성 중 공연은 공개 조건(ASSIGNED 1개 이상)을 영영 채우지 못한다.
	 * 전석 섞임은 구역 설정(SLR-11)이 이미 막는다. 공개 때만 한 번 더 보는 것이라 여기서는 보지 않는다.
	 */
	private void validateZonesForSeats(List<ZoneGrade> zoneGrades, List<VenueZone> venueZones, String unsetMessage,
		String noAssignedMessage) {
		List<VenueZone> unsetZones = zonesOf(zoneGrades, ZoneAssignmentStatus.UNSET, venueZones);
		if (!unsetZones.isEmpty()) {
			throw new BusinessException(PerformanceExceptionCode.ZONE_UNSET_REMAINS,
				unsetMessage + ": " + String.join(", ", unsetZones.stream().map(VenueZone::getName).toList()));
		}
		boolean hasAssigned = zoneGrades.stream()
			.anyMatch(zoneGrade -> zoneGrade.getAssignmentStatus() == ZoneAssignmentStatus.ASSIGNED);
		if (!hasAssigned) {
			throw new BusinessException(PerformanceExceptionCode.NO_ASSIGNED_ZONE, noAssignedMessage);
		}
	}

	/** 방금 만든 좌석을 DB에서 상태별로 센다. 만든 직후라 AVAILABLE 과 BLOCKED 만 있다. */
	private SellerPerformanceResponse.SeatsGenerated seatsGeneratedResponse(Long scheduleId, LocalDateTime now) {
		long available = 0;
		long blocked = 0;
		for (ScheduleSeatRepository.SeatStatusCount count : scheduleSeatRepository.countByStatus(List.of(scheduleId))) {
			if (count.getStatus() == SeatStatus.AVAILABLE) {
				available = count.getCount();
			} else if (count.getStatus() == SeatStatus.BLOCKED) {
				blocked = count.getCount();
			}
		}
		return SellerPerformanceResponse.SeatsGenerated.builder()
			.scheduleId(scheduleId)
			.seatsGeneratedAt(now)
			.totalSeats(available + blocked)
			.availableSeats(available)
			.blockedSeats(blocked)
			.build();
	}

	/** 판매 오픈·종료 일시, 1인 최대 매수, 수령 방법, 예매 수수료 중 비어 있는 것이 있는가. 공개와 좌석 생성이 같은 기준을 쓴다. */
	private boolean hasMissingSaleField(Performance performance) {
		return performance.getSaleOpenAt() == null || performance.getSaleCloseAt() == null
			|| performance.getMaxPerPerson() == null || performance.getReceiveType() == null
			|| performance.getBookingFee() == null;
	}

	/** 공연 한 행만 보고 판단하는 조건. 작성 중 → 판매 필드 → 판매 종료 → 판매 오픈 순이다. */
	private void validatePublishable(Performance performance, LocalDateTime now) {
		if (performance.getStatus() != PerformanceStatus.DRAFT) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_NOT_DRAFT);
		}
		if (hasMissingSaleField(performance)) {
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
	 * 취소되지 않은 회차(공연 일시가 빠른 순)와 판매 기간의 관계. 모든 회차가 판매 오픈보다 늦게 시작해야 하며,
	 * 판매 종료가 마지막 회차 시작보다 늦으면 안 된다. 판매 종료 뒤에 공연하는 회차는 미리 파는 것이라 막지 않는다.
	 * 공개(저장된 값)와 공개 후 수정(저장 전의 요청 값)이 같은 규칙을 쓰도록 오픈·종료를 값으로 받는다.
	 * 회차가 없으면 비교할 것이 없어 통과시킨다. (공개는 회차가 있는지 먼저 확인한다)
	 */
	private void validateSaleWindow(LocalDateTime saleOpenAt, LocalDateTime saleCloseAt,
		List<PerformanceSchedule> schedules) {
		if (schedules.isEmpty()) {
			return;
		}
		List<String> tooEarly = schedules.stream()
			.map(PerformanceSchedule::getStartAt)
			.filter(startAt -> !startAt.isAfter(saleOpenAt))
			.map(SCHEDULE_TIME_FORMAT::format)
			.toList();
		if (!tooEarly.isEmpty()) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_BEFORE_SALE_OPEN,
				PerformanceExceptionCode.SCHEDULE_BEFORE_SALE_OPEN.getMessage() + ": " + String.join(", ", tooEarly));
		}
		if (saleCloseAt.isAfter(schedules.getLast().getStartAt())) {
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
	 * 등록·수정 요청 값만 보고 하는 검사. DB 를 쓰지 않아서 조회보다 먼저 한다. (판매 기간 순서, 이미지 순서값 중복)
	 * 남의 공연에 보내도 요청만 보는 검사라 남의 공연 정보가 드러나지 않는다.
	 */
	public void validate(SellerPerformanceRequest.Save request) {
		validateSalePeriod(request.saleOpenAt(), request.saleCloseAt());
		validateImageOrders(request.images());
	}

	/**
	 * 수정할 내 공연을 읽는다. 남의 공연은 없는 공연과 같은 404 다. 호출한 쪽의 트랜잭션에 참여하고,
	 * 락 밖에서 부르면 번호만 쓰는 필드(공연장 번호, 러닝타임)만 믿고 지연 로딩 연관은 꺼내지 않는다.
	 */
	@Transactional(readOnly = true)
	public Performance getMinePerformance(Long businessId, Long performanceId) {
		return getMineEntity(businessId, performanceId);
	}

	/**
	 * 공연 한 행만 보고 판단하는 수정 조건. 취소된 공연은 막고, 공개(PUBLISHED)된 공연은 공연장·취소 규정이 바뀌는지,
	 * 판매 일시가 규칙에 맞는지 본다. 작성 중(DRAFT)은 요청 형식 검사(등록과 같은 규칙) 외에 제한이 없다.
	 * 공연장·취소 규정은 전체 교체 PUT 이라 매번 함께 오므로, 같은 값이면 통과시키고 값이 실제로 바뀔 때만 막는다.
	 * 순서: 취소 → 공연장 → 취소 규정 → 판매 필드 → 판매 오픈 → 판매 종료 → 오픈 3시간 전.
	 */
	public void validateUpdatable(Performance performance, SellerPerformanceRequest.Save request, Long venueId,
		Long cancelPolicyId, LocalDateTime now) {
		if (performance.getStatus() == PerformanceStatus.CANCELED) {
			throw new BusinessException(PerformanceExceptionCode.PERFORMANCE_CANCELED);
		}
		if (performance.getStatus() != PerformanceStatus.PUBLISHED) {
			return;
		}
		if (!performance.getVenueId().equals(venueId)) {
			throw new BusinessException(PerformanceExceptionCode.VENUE_CHANGE_NOT_ALLOWED);
		}
		if (!performance.getCancelPolicy().getId().equals(cancelPolicyId)) {
			throw new BusinessException(PerformanceExceptionCode.CANCEL_POLICY_CHANGE_NOT_ALLOWED);
		}
		// 공개된 공연은 판매 필드가 모두 있어야 예매가 판매 기간을 읽을 수 있다. 비워 보내면 공개 때와 같은 이유로 막는다.
		if (request.saleOpenAt() == null || request.saleCloseAt() == null || request.maxPerPerson() == null
			|| request.receiveType() == null || request.bookingFee() == null) {
			throw new BusinessException(PerformanceExceptionCode.SALE_FIELDS_MISSING,
				"공개된 공연은 판매 오픈·종료 일시, 1인 최대 매수, 수령 방법, 예매 수수료를 비울 수 없습니다.");
		}

		boolean saleOpened = !performance.getSaleOpenAt().isAfter(now);
		boolean openChanged = !request.saleOpenAt().equals(performance.getSaleOpenAt());
		if (saleOpened && openChanged) {
			throw new BusinessException(PerformanceExceptionCode.SALE_OPEN_CHANGE_NOT_ALLOWED);
		}
		// 종료는 늘리든 줄이든 새 종료가 지금보다 늦으면 된다. 판매자가 일찍 마감할 수 있게 기존 종료와 비교하지 않는다.
		if (!request.saleCloseAt().isAfter(now)) {
			throw new BusinessException(PerformanceExceptionCode.SALE_CLOSED);
		}
		// 판매 오픈 전에 오픈을 옮기는 경우만 공개 때와 같은 기준(3시간)을 본다. 오픈을 그대로 두면 이미 통과한 값이다.
		if (openChanged && !request.saleOpenAt().isAfter(now.plus(PUBLISH_LEAD_TIME))) {
			throw new BusinessException(PerformanceExceptionCode.SALE_OPEN_TOO_SOON);
		}
	}

	/**
	 * 공개된 공연의 새 판매 일시가 취소되지 않은 회차와 맞는지 본다. 공개 때와 같은 규칙이다.
	 * 작성 중 공연은 회차가 판매 기간과 맞는지 공개 때 보므로 여기서 보지 않는다.
	 */
	public void validateScheduleRules(Performance performance, SellerPerformanceRequest.Save request) {
		if (performance.getStatus() != PerformanceStatus.PUBLISHED) {
			return;
		}
		List<PerformanceSchedule> schedules = performanceScheduleRepository
			.findByPerformanceIdAndStatusNotOrderByStartAt(performance.getId(), ScheduleStatus.CANCELED);
		validateSaleWindow(request.saleOpenAt(), request.saleCloseAt(), schedules);
	}

	/**
	 * 이 공연의 취소되지 않은 회차가 venueId 공연장의 다른 회차와 시간이 겹치지 않는지 본다. 공연장을 바꿀 때(새 공연장)와
	 * 러닝타임을 늘릴 때(지금 공연장) 부른다.
	 * 락 없이 검사만 한다. 서로 다른 공연이 같은 순간에 같은 공연장의 같은 시간을 잡아 둘 다 통과할 수 있지만, 공연장은 판매자가
	 * 미리 정해 오는 것이라 매우 드물다고 보고 알고 감수한다. (DB 제약은 doc/seller-progress.md 의 TODO)
	 * 겹치는 이 공연의 회차 일시는 메시지로 알려 주고, 다른 공연의 회차 일시는 다른 판매자의 정보라 알려 주지 않는다.
	 */
	public void validateNoOverlap(Long performanceId, Long venueId, int runningTime) {
		List<LocalDateTime> overlapped = performanceScheduleRepository.findOverlappingStartAts(performanceId, venueId,
			runningTime, (int) SCHEDULE_BUFFER.toMinutes());
		if (!overlapped.isEmpty()) {
			throw new BusinessException(PerformanceExceptionCode.SCHEDULE_TIME_OVERLAP,
				PerformanceExceptionCode.SCHEDULE_TIME_OVERLAP.getMessage() + ": "
					+ String.join(", ", overlapped.stream().map(SCHEDULE_TIME_FORMAT::format).toList()));
		}
	}

	/**
	 * 공연의 일반·판매 필드와 상세 이미지를 전체 교체한다. 상태는 바꾸지 않는다.
	 * 공연장·취소 규정은 작성 중에 실제로 바뀔 때만 값을 넘기고 아니면 null 이다. (구역 설정·판매 좌석 정리는 호출한 쪽이 한다)
	 * 호출한 쪽이 읽어 온 공연 엔티티를 받아 같은 트랜잭션에서 바꾸므로 커밋 때 저장된다.
	 */
	@Transactional
	public Performance update(Performance performance, SellerPerformanceRequest.Save request, Long newVenueId,
		CancelPolicy newCancelPolicy) {
		performance.update(request.title(), request.genre(), request.ageLimit(), request.runningTime(),
			request.castInfo(), request.posterUrl(), request.notice(), request.guide(), request.saleOpenAt(),
			request.saleCloseAt(), request.maxPerPerson(), request.receiveType(), request.bookingFee());
		if (newVenueId != null) {
			performance.changeVenue(newVenueId);
		}
		if (newCancelPolicy != null) {
			performance.changeCancelPolicy(newCancelPolicy);
		}

		performanceImageRepository.deleteByPerformanceId(performance.getId());
		performanceImageRepository.saveAll(request.images().stream()
			.map(image -> PerformanceImage.builder()
				.performance(performance)
				.url(image.url())
				.sortOrder(image.sortOrder())
				.build())
			.toList());
		return performance;
	}

	/**
	 * 공연을 작성 중(DRAFT)으로 등록하고 상세 이미지를 저장한다.
	 * 공연장·취소 규정은 호출하는 쪽(파사드)이 각 서비스로 확인한 값을 받는다. 판매 필드는 DRAFT 에서 비워 둘 수 있다.
	 */
	@Transactional
	public Performance create(Long businessId, Long createdBy, Long venueId, CancelPolicy cancelPolicy,
		SellerPerformanceRequest.Save request) {
		validate(request);

		Performance performance = performanceRepository.save(Performance.builder()
			.businessId(businessId)
			.venueId(venueId)
			.createdBy(createdBy)
			.cancelPolicy(cancelPolicy)
			.title(request.title())
			.genre(request.genre())
			.ageLimit(request.ageLimit())
			.runningTime(request.runningTime())
			.castInfo(request.castInfo())
			.posterUrl(request.posterUrl())
			.notice(request.notice())
			.guide(request.guide())
			.status(PerformanceStatus.DRAFT)
			.saleOpenAt(request.saleOpenAt())
			.saleCloseAt(request.saleCloseAt())
			.maxPerPerson(request.maxPerPerson())
			.receiveType(request.receiveType())
			.bookingFee(request.bookingFee())
			.build());

		performanceImageRepository.saveAll(request.images().stream()
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
	private void validateImageOrders(List<SellerPerformanceRequest.Image> images) {
		long distinctOrders = images.stream().map(SellerPerformanceRequest.Image::sortOrder).distinct().count();
		if (distinctOrders != images.size()) {
			throw new BusinessException(CommonExceptionCode.INVALID_INPUT, "이미지 순서(sortOrder)가 중복되었습니다.");
		}
	}

	/**
	 * 중지·재개 결과. changed 는 바꾼 회차이고, 일괄 UPDATE 라 바꾸기 전 상태와 중지 사유가 남아 있어 감사 로그에 쓴다.
	 */
	public record ScheduleSaleChange(SellerPerformanceResponse.ScheduleSaleChanged response,
		List<PerformanceSchedule> changed) {
	}
}
