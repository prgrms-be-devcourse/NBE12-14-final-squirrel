package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.CancelPolicy;
import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceImageRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceRepository;
import com.squirrel.tickting.domain.performance.infra.repository.PerformanceScheduleRepository;
import com.squirrel.tickting.domain.performance.type.PerformanceStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.CommonExceptionCode;
import com.squirrel.tickting.global.exception.code.PerformanceExceptionCode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceService {

	private final PerformanceRepository performanceRepository;
	private final PerformanceImageRepository performanceImageRepository;
	private final PerformanceScheduleRepository performanceScheduleRepository;

	/** 내 사업체의 공연 목록. 상태를 주지 않으면 취소된 공연까지 모든 상태가 나온다. */
	@Transactional(readOnly = true)
	public Page<Performance> searchMine(Long businessId, PerformanceStatus status, Pageable pageable) {
		var statuses = status == null ? EnumSet.allOf(PerformanceStatus.class) : EnumSet.of(status);
		return performanceRepository.search(businessId, statuses, pageable);
	}

	/** 공연별 회차 수. 회차가 없는 공연은 결과에 없으므로 호출하는 쪽이 0으로 본다. */
	@Transactional(readOnly = true)
	public Map<Long, Long> countSchedules(Collection<Long> performanceIds) {
		if (performanceIds.isEmpty()) {
			return Map.of();
		}
		return performanceScheduleRepository.countByPerformanceIds(performanceIds).stream()
			.collect(Collectors.toMap(count -> count.getPerformanceId(), count -> count.getScheduleCount()));
	}

	/** 내 사업체의 공연 상세. 남의 공연은 없는 공연과 같은 404로 답한다. */
	@Transactional(readOnly = true)
	public PerformanceDetail getMine(Long businessId, Long performanceId) {
		Performance performance = performanceRepository.findByIdAndBusinessId(performanceId, businessId)
			.orElseThrow(() -> new BusinessException(PerformanceExceptionCode.PERFORMANCE_NOT_FOUND));
		return new PerformanceDetail(
			performance,
			performanceImageRepository.findByPerformanceIdOrderBySortOrder(performanceId),
			performanceScheduleRepository.findByPerformanceIdOrderByStartAt(performanceId));
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
