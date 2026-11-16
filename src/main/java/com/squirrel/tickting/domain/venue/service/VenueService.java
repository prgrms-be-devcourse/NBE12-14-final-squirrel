package com.squirrel.tickting.domain.venue.service;

import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.infra.repository.VenueRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueSeatRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueZoneRepository;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.exception.BusinessException;
import com.squirrel.tickting.global.exception.code.VenueExceptionCode;
import com.squirrel.tickting.global.util.LikeUtil;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VenueService {

	private final VenueRepository venueRepository;
	private final VenueZoneRepository venueZoneRepository;
	private final VenueSeatRepository venueSeatRepository;

	/**
	 * 판매자가 공연을 등록할 때 고르는 공연장 목록. 사용 중인 공연장만, 이름순으로 준다.
	 * 지역이 비어 있으면 전체 지역이고, 있으면 정확히 같은 지역만 찾는다.
	 * 검색어는 LikeUtil 로 LIKE 패턴으로 바꿔서 넘긴다. (%, _ 를 글자 그대로 찾고, 비면 전체가 일치하는 패턴이 된다)
	 */
	@Transactional(readOnly = true)
	public Page<Venue> searchActive(String region, String keyword, Pageable pageable) {
		// 지역이 없으면 쿼리가 "모든 지역"으로 읽도록 null 대신 빈 문자열을 넘긴다.
		String regionCondition = region == null ? "" : region.trim();
		return venueRepository.search(VenueStatus.ACTIVE, regionCondition, LikeUtil.containsPattern(keyword), pageable);
	}

	/** 공연장 번호별 이름. 사용 중지된 공연장도 포함한다. (이미 잡힌 공연의 장소는 계속 보여야 한다) */
	@Transactional(readOnly = true)
	public Map<Long, String> getNames(Collection<Long> venueIds) {
		return venueRepository.findAllById(venueIds).stream()
			.collect(Collectors.toMap(Venue::getId, Venue::getName));
	}

	/**
	 * 공연장 번호별 공연장. 예매자 공연 목록처럼 이름 말고 지역까지 필요한 곳에서 쓴다.
	 * getNames 와 같이 사용 중지된 공연장도 포함하고, 번호 목록으로 한 번에 읽어 공연마다 조회하지 않게 한다.
	 */
	@Transactional(readOnly = true)
	public Map<Long, Venue> getVenues(Collection<Long> venueIds) {
		return venueRepository.findAllById(venueIds).stream()
			.collect(Collectors.toMap(Venue::getId, Function.identity()));
	}

	/**
	 * 지역이 정확히 같은 공연장의 번호 목록. 예매자 공연 목록의 지역 검색에서 쓴다.
	 * getVenues 와 같이 사용 중지된 공연장도 포함한다. (이미 잡힌 공연은 공연장이 중지돼도 그 지역에서 찾을 수 있어야 한다)
	 */
	@Transactional(readOnly = true)
	public List<Long> getIdsByRegion(String region) {
		return venueRepository.findAllByRegion(region).stream()
			.map(Venue::getId)
			.toList();
	}

	/**
	 * 새 공연을 등록할 수 있는 공연장의 구역 번호 목록. 공연은 공연장의 모든 구역마다 구역 설정을 하나씩 만든다.
	 * 사용 중지된 공연장은 선택 목록(searchActive)에서 빠지지만 번호를 직접 보낼 수 있어서 여기서 한 번 더 막는다.
	 * 구역이 없는 공연장도 통과시킨다. (판매할 구역이 없으면 공개(SLR-04)의 "ASSIGNED 구역 1개 이상" 조건에서 막힌다)
	 */
	@Transactional(readOnly = true)
	public List<Long> getActiveZoneIds(Long venueId) {
		Venue venue = venueRepository.findById(venueId)
			.orElseThrow(() -> new BusinessException(VenueExceptionCode.VENUE_NOT_FOUND));
		if (venue.getStatus() != VenueStatus.ACTIVE) {
			throw new BusinessException(VenueExceptionCode.VENUE_INACTIVE);
		}
		return venueZoneRepository.findIdsByVenueId(venueId);
	}
}
