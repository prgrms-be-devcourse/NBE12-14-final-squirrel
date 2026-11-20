package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.dto.UserPerformanceResponse;
import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicyRule;
import com.squirrel.tickting.domain.performance.infra.entity.CommonGuide;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import com.squirrel.tickting.domain.performance.infra.entity.SeatGrade;
import com.squirrel.tickting.domain.performance.infra.repository.CancelPolicyRuleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.CommonGuideRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceImageRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository.ZoneName;
import com.squirrel.tickting.domain.performance.infra.repository.UserPerformanceRepository.ZoneRemaining;
import com.squirrel.tickting.domain.performance.type.Genre;
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
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 예매자(User) 화면의 공연 조회. 공연 도메인을 판매자와 함께 쓰므로, 같은 파일을 동시에 고치지 않게 역할 이름을 붙여 나눈다.
 * 공연장·사업체·회차·좌석은 공연의 읽기 전용 연관에서 꺼내므로, 지연 로딩이 되도록 응답을 이 트랜잭션 안에서 만든다.
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
	 * 공개된 공연 목록·검색. 장르를 주지 않으면(null) 전체 장르이고, 지역을 주지 않으면(null·공백) 전체 지역이다.
	 * 지역은 공연장에 있어서 공연장을 조인해서 지역이 정확히 같은 공연만 찾는다. 검색어는 제목·출연진의 부분 일치다.
	 * pageable 은 페이지 번호와 크기만 쓰고, 정렬은 sortType 으로만 정한다.
	 *
	 * 첫·마지막 회차 일시는 공연의 회차 목록에서, 남은 좌석은 이 페이지의 회차만 골라 좌석을 한 번 더 읽어서 계산한다.
	 * (회차·공연장은 지연 로딩이지만 한 페이지 분량을 묶어서 읽어서 공연마다 쿼리가 나가지 않는다)
	 *   - 취소된 회차는 공연 일시에서 뺀다. 회차가 전부 취소된 공연은 일시가 비고(null) 판매 상태가 종료다.
	 *   - 매진 판단은 아직 시작하지 않은 회차의 AVAILABLE 좌석만 본다. 임시 점유(HELD)만 남았으면 지금은 살 수 없으니 매진이고,
	 *     점유가 풀려 AVAILABLE 로 돌아오면 다음 조회부터 다시 판매 중이 된다. (상태를 저장하지 않고 계산하므로 따로 되돌릴 일이 없다)
	 * 현재 시각은 여기서 한 번만 구해서 남은 좌석 판단과 판매 상태 계산에 같이 쓴다.
	 */
	@Transactional(readOnly = true)
	public Page<UserPerformanceResponse.Summary> getPublishedList(Genre genre, String region, String keyword,
		UserPerformanceSortType sortType, Pageable pageable) {
		LocalDateTime now = LocalDateTime.now();
		Collection<Genre> genres = genre == null ? EnumSet.allOf(Genre.class) : EnumSet.of(genre);
		boolean allRegions = region == null || region.isBlank();
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortType.getSort());

		Page<Performance> performances = userPerformanceRepository.findPublished(genres, allRegions,
			allRegions ? "" : region.trim(), LikeUtil.containsPattern(keyword), sorted);
		List<Long> upcomingScheduleIds = performances.stream()
			.flatMap(performance -> activeSchedules(performance).stream())
			.filter(schedule -> schedule.getStartAt().isAfter(now))
			.map(PerformanceSchedule::getId)
			.toList();
		Set<Long> scheduleIdsWithSeat = upcomingScheduleIds.isEmpty() ? Set.of()
			: new HashSet<>(userPerformanceRepository.findScheduleIdsWithAvailableSeat(upcomingScheduleIds));

		return performances.map(performance -> UserPerformanceResponse.Summary.from(
			summarize(performance, activeSchedules(performance), scheduleIdsWithSeat, now)));
	}

	/**
	 * 공개된 공연 하나의 상세. 작성 중·취소된 공연은 없는 공연과 같은 404 다. (번호가 존재하는지 알려주지 않으려고)
	 * 공연장·사업체는 공연의 읽기 전용 연관에서, 이미지·회차·등급·취소 규정·공통 안내는 이 트랜잭션 안에서 읽는다.
	 *
	 *   - 회차: 취소된 회차는 뺀다. 공연 목록의 첫·마지막 회차 일시와 같은 기준이다.
	 *     판매 상태는 회차마다 목록과 같은 SaleStatus 규칙으로 계산한다. (마지막 회차 자리에 그 회차의 시작 일시)
	 *     일시 중지·연기는 판매 상태에 섞지 않고 회차 상태(status)로 따로 보여 준다.
	 *   - 공통 안내: 유형마다 한 행이다. 아직 넣지 않은 유형은 응답에서 null 이다.
	 */
	@Transactional(readOnly = true)
	public UserPerformanceResponse.Detail getPublishedDetail(Long performanceId) {
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

		return UserPerformanceResponse.Detail.from(new Detail(
			performance,
			performanceImageRepository.findByPerformanceIdOrderBySortOrder(performanceId),
			scheduleItems,
			userPerformanceRepository.findAssignedGrades(performanceId),
			cancelPolicyRuleRepository.findByPolicyIdOrderByDaysFromDesc(performance.getCancelPolicy().getId()),
			commonGuideRepository.findAll()));
	}

	/**
	 * 회차의 구역 요약. 공개 공연의 취소되지 않은 회차만 볼 수 있고, 그 밖은 없는 회차와 같은 404 다.
	 * 구역은 공연장의 읽기 전용 연관(구역 이름·층·좌석 수)에서, 등급과 잔여석은 공연의 구역 설정과 좌석에서 읽는다.
	 *
	 *   - 구역별 등급: 등급 이름·색·가격을 함께 읽는다. 미판매(INTENTIONALLY_UNSOLD) 구역은 등급이 없다.
	 *   - 잔여석: 판매 가능(AVAILABLE) 좌석만 센다. 임시 점유(HELD)·판매 완료·판매자 보류·판매 제외는 지금 살 수 없어서 빠진다.
	 *     판매 좌석을 아직 만들지 않은 회차(판매 오픈 24시간 전보다 이전)는 살 수 있는 좌석이 없으니 세지 않고 모든 구역이 0 이다.
	 */
	@Transactional(readOnly = true)
	public UserPerformanceResponse.ZoneSummary getZoneSummary(Long scheduleId) {
		PerformanceSchedule schedule = userPerformanceRepository.findVisibleSchedule(scheduleId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.USER_SCHEDULE_NOT_FOUND));

		List<ZoneRemaining> remainings = schedule.getSeatsGeneratedAt() == null ? List.of()
			: userPerformanceRepository.countAvailableSeatsByZone(scheduleId);
		return UserPerformanceResponse.ZoneSummary.from(schedule,
			userPerformanceRepository.findZoneGrades(schedule.getPerformance().getId()), remainings);
	}

	/**
	 * 회차의 한 구역에 있는 판매 좌석. 볼 수 있는 회차 기준은 구역 요약과 같다. (공개 공연의 취소되지 않은 회차)
	 * 판매 좌석을 아직 만들지 않은 회차는 고를 좌석이 없어서 404 다.
	 * 구역은 그 회차 공연장의 구역이어야 한다. 다른 공연장의 구역 번호로 좌석이 섞여 보이지 않게, 없는 구역과 같은 404 다.
	 */
	@Transactional(readOnly = true)
	public UserPerformanceResponse.ZoneSeats getZoneSeats(Long scheduleId, Long zoneId) {
		PerformanceSchedule schedule = userPerformanceRepository.findVisibleSchedule(scheduleId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.USER_SCHEDULE_NOT_FOUND));
		if (schedule.getSeatsGeneratedAt() == null) {
			throw new BusinessException(PerformanceExceptionCode.USER_SEATS_NOT_GENERATED);
		}
		// 공연장의 구역 전체를 읽어서 찾지 않고 그 공연장의 구역 한 건만 읽는다. (구역마다 좌석 수를 세는 서브쿼리가 같이 나가서 비쌌다)
		ZoneName zone = userPerformanceRepository.findZoneName(zoneId, schedule.getPerformance().getVenueId())
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.USER_ZONE_NOT_FOUND));
		return UserPerformanceResponse.ZoneSeats.from(zone, userPerformanceRepository.findScheduleSeats(scheduleId, zoneId));
	}

	/** 취소되지 않은 회차. 공연의 회차 목록(읽기 전용 연관)에서 거른다. */
	private List<PerformanceSchedule> activeSchedules(Performance performance) {
		return performance.getSchedules().stream()
			.filter(schedule -> schedule.getStatus() != ScheduleStatus.CANCELED)
			.toList();
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

	/** 공연 상세의 재료. 공연장·사업체는 공연의 읽기 전용 연관에서 꺼내므로 담지 않는다. */
	public record Detail(
		Performance performance,
		List<PerformanceImage> images,
		List<ScheduleItem> schedules,
		List<SeatGrade> grades,
		List<CancelPolicyRule> cancelRules,
		List<CommonGuide> commonGuides
	) {
	}

	/** 상세의 회차 하나와 그 회차의 판매 상태. */
	public record ScheduleItem(PerformanceSchedule schedule, SaleStatus saleStatus) {
	}
}
