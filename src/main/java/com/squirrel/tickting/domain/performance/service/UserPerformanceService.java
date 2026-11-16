package com.squirrel.tickting.domain.performance.service;

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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	 * 현재 시각은 여기서 한 번만 구해서 쿼리(남은 좌석, 임박순)와 판매 상태 계산에 같이 쓴다.
	 * pageable 은 페이지 번호와 크기만 쓰고, 정렬은 sortType 으로만 정한다.
	 */
	@Transactional(readOnly = true)
	public Page<Summary> getPublishedList(Genre genre, Collection<Long> venueIds, String keyword,
		UserPerformanceSortType sortType, Pageable pageable) {
		LocalDateTime now = LocalDateTime.now();
		Collection<Genre> genres = genre == null ? EnumSet.allOf(Genre.class) : EnumSet.of(genre);
		boolean allVenues = venueIds == null;
		Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortType.getSort());

		return userPerformanceRepository.findPublished(genres, allVenues, allVenues ? List.of() : venueIds,
				LikeUtil.containsPattern(keyword), now, sorted)
			.map(row -> new Summary(row, saleStatus(row, now)));
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

	private SaleStatus saleStatus(UserPerformanceRepository.SummaryRow row, LocalDateTime now) {
		return SaleStatus.of(row.saleOpenAt(), row.saleCloseAt(), row.lastStartAt(), row.hasAvailableSeat(), now);
	}

	/** 공연 목록의 한 항목. 쿼리로 읽은 값과 그 값으로 계산한 판매 상태를 함께 담는다. */
	public record Summary(UserPerformanceRepository.SummaryRow row, SaleStatus saleStatus) {
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
}
