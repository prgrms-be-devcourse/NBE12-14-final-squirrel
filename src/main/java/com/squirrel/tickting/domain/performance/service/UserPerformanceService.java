package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.entity.CommonGuide;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.ScheduleSeat;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.entity.ZoneGrade;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CommonGuideRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceImageRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository;
import com.squirrel.tickting.domain.performance.type.Genre;
import com.squirrel.tickting.domain.performance.type.GuideType;
import com.squirrel.tickting.domain.performance.type.SaleStatus;
import com.squirrel.tickting.domain.performance.type.ScheduleStatus;
import com.squirrel.tickting.domain.performance.type.UserPerformanceSortType;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import com.squirrel.tickting.global.util.LikeUtil;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매자(User) 화면의 공연 조회. 공연 도메인을 판매자와 함께 쓰므로, 같은 파일을 동시에 고치지 않게 역할 이름을 붙여 나눈다.
 * 공연 도메인의 값만 돌려주고, 공연장 이름·지역은 파사드가 붙인다.
 */
@Service
@RequiredArgsConstructor
public class UserPerformanceService {

	private final UserPerformanceRepository userPerformanceRepository;
	private final PerformanceImageRepository performanceImageRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;
	private final CancelPolicyRuleRepository cancelPolicyRuleRepository;
	private final CommonGuideRepository commonGuideRepository;

	/**
	 * 공개된 공연 목록·검색. 장르를 주지 않으면(null) 전체 장르다.
	 * venueIds 가 null 이면 공연장(지역)을 따지지 않고, 있으면 그 공연장의 공연만 찾는다. 검색어는 제목·출연진의 부분 일치다.
	 * pageable 은 페이지 번호와 크기만 쓰고, 정렬은 sortType 으로만 정한다.
	 *
	 * 첫·마지막 회차 일시와 남은 좌석은 이 페이지의 공연만 골라 회차·좌석을 한 번씩 더 읽어서 계산한다.
	 * (공연 수와 상관없이 쿼리는 목록·개수·회차·좌석 네 번이다)
	 *   - 취소된 회차는 공연 일시에서 뺀다. 회차가 전부 취소된 공연은 일시가 비고(null) 판매 상태가 종료다.
	 *   - 매진 판단은 아직 시작하지 않은 회차의 AVAILABLE 좌석만 본다. 임시 점유(HELD)만 남았으면 지금은 살 수 없으니 매진이고,
	 *     점유가 풀려 AVAILABLE 로 돌아오면 다음 조회부터 다시 판매 중이 된다. (상태를 저장하지 않고 계산하므로 따로 되돌릴 일이 없다)
	 * 현재 시각은 여기서 한 번만 구해서 남은 좌석 판단과 판매 상태 계산에 같이 쓴다.
	 */
	@Transactional(readOnly = true)
	public Page<Summary> getPublishedList(Genre genre, Collection<Long> venueIds, String keyword,
		UserPerformanceSortType sortType, Pageable pageable) {
		LocalDateTime now = LocalDateTime.now();
		Collection<Genre> genres = genre == null ? EnumSet.allOf(Genre.class) : EnumSet.of(genre);
		boolean allVenues = venueIds == null;
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortType.getSort());

		Page<Performance> performances = userPerformanceRepository.findPublished(genres, allVenues,
			allVenues ? List.of() : venueIds, LikeUtil.containsPattern(keyword), sorted);
		List<Long> performanceIds = performances.map(Performance::getId).toList();
		Map<Long, List<PerformanceSchedule>> schedulesByPerformance = performanceIds.isEmpty() ? Map.of()
			: userPerformanceRepository.findActiveSchedules(performanceIds).stream()
				.collect(Collectors.groupingBy(schedule -> schedule.getPerformance().getId()));
		List<Long> upcomingScheduleIds = schedulesByPerformance.values().stream()
			.flatMap(List::stream)
			.filter(schedule -> schedule.getStartAt().isAfter(now))
			.map(PerformanceSchedule::getId)
			.toList();
		Set<Long> scheduleIdsWithSeat = upcomingScheduleIds.isEmpty() ? Set.of()
			: new HashSet<>(userPerformanceRepository.findScheduleIdsWithAvailableSeat(upcomingScheduleIds));

		return performances.map(performance -> summarize(performance,
			schedulesByPerformance.getOrDefault(performance.getId(), List.of()), scheduleIdsWithSeat, now));
	}

	/**
	 * 공개된 공연 하나의 상세. 작성 중·취소된 공연은 없는 공연과 같은 404 다. (번호가 존재하는지 알려주지 않으려고)
	 * 공연 도메인의 값(이미지, 회차, 등급, 취소 규정, 공통 안내)을 이 트랜잭션 안에서 모두 읽어 담는다.
	 * 취소 규정·등급은 지연 로딩이라 트랜잭션이 끝난 뒤 꺼내면 LazyInitializationException 이 나기 때문이다.
	 *
	 *   - 회차: 취소된 회차는 뺀다. 공연 목록의 첫·마지막 회차 일시와 같은 기준이다.
	 *     판매 상태는 회차마다 목록과 같은 SaleStatus 규칙으로 계산한다. (마지막 회차 자리에 그 회차의 시작 일시)
	 *     일시 중지·연기는 판매 상태에 섞지 않고 회차 상태(status)로 따로 보여 준다.
	 *   - 공통 안내: 유형마다 한 행이다. 아직 넣지 않은 유형은 Map 에 없다.
	 */
	@Transactional(readOnly = true)
	public Detail getPublishedDetail(Long performanceId) {
		Performance performance = userPerformanceRepository.findPublishedById(performanceId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND));
		LocalDateTime now = LocalDateTime.now();

		List<PerformanceSchedule> schedules = performanceScheduleRepository.findByPerformanceIdOrderByStartAt(performanceId)
			.stream()
			.filter(schedule -> schedule.getStatus() != ScheduleStatus.CANCELED)
			.toList();
		Set<Long> scheduleIdsWithSeat = new HashSet<>(userPerformanceRepository.findScheduleIdsWithAvailableSeat(
			schedules.stream().map(PerformanceSchedule::getId).toList()));
		List<ScheduleItem> scheduleItems = schedules.stream()
			.map(schedule -> new ScheduleItem(schedule, SaleStatus.of(performance.getSaleOpenAt(),
				performance.getSaleCloseAt(), schedule.getStartAt(), scheduleIdsWithSeat.contains(schedule.getId()), now)))
			.toList();

		Map<GuideType, String> commonGuides = commonGuideRepository.findAll().stream()
			.collect(Collectors.toMap(CommonGuide::getType, CommonGuide::getContent));

		return new Detail(
			performance,
			performanceImageRepository.findByPerformanceIdOrderBySortOrder(performanceId),
			scheduleItems,
			userPerformanceRepository.findAssignedGrades(performanceId),
			cancelPolicyRuleRepository.findByPolicyIdOrderByDaysFromDesc(performance.getCancelPolicy().getId()),
			commonGuides);
	}

	/**
	 * 회차의 구역 요약에 쓰는 공연 도메인의 값. 공개 공연의 취소되지 않은 회차만 볼 수 있고, 그 밖은 없는 회차와 같은 404 다.
	 *
	 *   - 구역별 등급: 등급 이름·색·가격을 함께 읽는다. 미판매(INTENTIONALLY_UNSOLD) 구역은 등급이 없다.
	 *   - 잔여석: 판매 가능(AVAILABLE) 좌석만 센다. 임시 점유(HELD)·판매 완료·판매자 보류·판매 제외는 지금 살 수 없어서 빠진다.
	 *     판매 좌석을 아직 만들지 않은 회차(판매 오픈 24시간 전보다 이전)는 살 수 있는 좌석이 없으니 세지 않고 모든 구역이 0 이다.
	 */
	@Transactional(readOnly = true)
	public ZoneSummary getZoneSummary(Long scheduleId) {
		PerformanceSchedule schedule = userPerformanceRepository.findVisibleSchedule(scheduleId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.USER_SCHEDULE_NOT_FOUND));

		Map<Long, ZoneGrade> gradesByZone = userPerformanceRepository
			.findZoneGrades(schedule.getPerformance().getId()).stream()
			.collect(Collectors.toMap(ZoneGrade::getZoneId, Function.identity()));
		Map<Long, Integer> remainingByZone = schedule.getSeatsGeneratedAt() == null ? Map.of()
			: userPerformanceRepository.countAvailableSeatsByZone(scheduleId).stream()
				.collect(Collectors.toMap(
					row -> row.get("zoneId", Long.class),
					row -> row.get("remaining", Long.class).intValue()));

		return new ZoneSummary(schedule, gradesByZone, remainingByZone);
	}

	/**
	 * 회차의 한 구역에 있는 판매 좌석. 볼 수 있는 회차 기준은 구역 요약과 같다. (공개 공연의 취소되지 않은 회차)
	 * 판매 좌석을 아직 만들지 않은 회차는 고를 좌석이 없어서 404 다. 구역이 그 회차의 공연장 것인지는 공연장 도메인이라 파사드가 확인한다.
	 */
	@Transactional(readOnly = true)
	public ZoneSeats getZoneSeats(Long scheduleId, Long zoneId) {
		PerformanceSchedule schedule = userPerformanceRepository.findVisibleSchedule(scheduleId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.USER_SCHEDULE_NOT_FOUND));
		if (schedule.getSeatsGeneratedAt() == null) {
			throw new BusinessException(PerformanceExceptionCode.USER_SEATS_NOT_GENERATED);
		}
		return new ZoneSeats(schedule, userPerformanceRepository.findScheduleSeats(scheduleId, zoneId));
	}

	/**
	 * 공연 번호 → 공연. 다른 도메인(예매 내역)이 번호로 제목·포스터를 붙일 때 쓴다.
	 * 공개 공연만 보여 주는 목록·상세와 달리 상태와 상관없이 준다. 예매한 뒤 공연이 취소돼도 내역에는 남아야 한다.
	 */
	@Transactional(readOnly = true)
	public Map<Long, Performance> getPerformances(Collection<Long> performanceIds) {
		if (performanceIds.isEmpty()) {
			return Map.of();
		}
		return userPerformanceRepository.findAllByIds(performanceIds).stream()
			.collect(Collectors.toMap(Performance::getId, Function.identity()));
	}

	/** 회차 번호 → 회차. 예매 내역의 공연 일시에 쓴다. 연기되면 바뀐 일시가 보이도록 예매 때 복사하지 않고 지금 값을 읽는다. */
	@Transactional(readOnly = true)
	public Map<Long, PerformanceSchedule> getSchedules(Collection<Long> scheduleIds) {
		if (scheduleIds.isEmpty()) {
			return Map.of();
		}
		return userPerformanceRepository.findSchedulesByIds(scheduleIds).stream()
			.collect(Collectors.toMap(PerformanceSchedule::getId, Function.identity()));
	}

	/** 공연 하나의 취소되지 않은 회차로 첫·마지막 회차 일시와 판매 상태를 계산한다. 회차가 없으면 일시는 null 이다. */
	private Summary summarize(Performance performance, List<PerformanceSchedule> schedules,
		Set<Long> scheduleIdsWithSeat, LocalDateTime now) {
		LocalDateTime firstStartAt = schedules.stream()
			.map(PerformanceSchedule::getStartAt)
			.min(Comparator.naturalOrder())
			.orElse(null);
		LocalDateTime lastStartAt = schedules.stream()
			.map(PerformanceSchedule::getStartAt)
			.max(Comparator.naturalOrder())
			.orElse(null);
		boolean hasAvailableSeat = schedules.stream()
			.anyMatch(schedule -> schedule.getStartAt().isAfter(now) && scheduleIdsWithSeat.contains(schedule.getId()));

		SaleStatus saleStatus = SaleStatus.of(performance.getSaleOpenAt(), performance.getSaleCloseAt(), lastStartAt,
			hasAvailableSeat, now);
		return new Summary(performance, firstStartAt, lastStartAt, saleStatus);
	}

	/**
	 * 공연 목록의 한 항목. 공연과, 취소되지 않은 회차로 계산한 값을 함께 담는다.
	 *
	 * @param firstStartAt 취소되지 않은 회차 중 가장 이른 공연 일시. 회차가 없으면 null
	 * @param lastStartAt  취소되지 않은 회차 중 가장 늦은 공연 일시. 회차가 없으면 null
	 */
	public record Summary(Performance performance, LocalDateTime firstStartAt, LocalDateTime lastStartAt,
		SaleStatus saleStatus) {
	}

	/** 공연 상세. 공연장·판매자는 다른 도메인이라 담지 않고 파사드가 붙인다. */
	public record Detail(
		Performance performance,
		List<PerformanceImage> images,
		List<ScheduleItem> schedules,
		List<SeatGrade> grades,
		List<CancelPolicyRule> cancelRules,
		Map<GuideType, String> commonGuides
	) {
	}

	/** 상세의 회차 하나와 그 회차의 판매 상태. */
	public record ScheduleItem(PerformanceSchedule schedule, SaleStatus saleStatus) {
	}

	/**
	 * 회차의 구역 요약 중 공연 도메인의 값. 구역 이름·순서와 구역별 좌석 수는 공연장 도메인이라 파사드가 붙인다.
	 *
	 * @param schedule        회차. 공연을 함께 읽어 두어 트랜잭션 밖에서 공연장 번호를 꺼낼 수 있다
	 * @param gradesByZone    구역 번호 → 구역 등급 설정
	 * @param remainingByZone 구역 번호 → 판매 가능 좌석 수. 판매 가능 좌석이 없는 구역은 없다
	 */
	public record ZoneSummary(PerformanceSchedule schedule, Map<Long, ZoneGrade> gradesByZone,
		Map<Long, Integer> remainingByZone) {
	}

	/**
	 * 회차의 한 구역 좌석 중 공연 도메인의 값. 열·번호·표시 순서와 구역 이름은 공연장 도메인이라 파사드가 붙인다.
	 *
	 * @param schedule 회차. 공연을 함께 읽어 두어 트랜잭션 밖에서 공연장 번호를 꺼낼 수 있다
	 * @param seats    그 구역의 판매 좌석 (상태 포함)
	 */
	public record ZoneSeats(PerformanceSchedule schedule, List<ScheduleSeat> seats) {
	}
}
