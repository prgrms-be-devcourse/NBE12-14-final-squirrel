package com.squirrel.tickting.domain.venue.service;

import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.infra.repository.VenueRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueSeatRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueZoneRepository;
import com.squirrel.tickting.domain.venue.type.VenueStatus;
import com.squirrel.tickting.global.util.LikeUtil;
import java.util.Collection;
import java.util.Map;
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
}
