package com.squirrel.tickting.domain.venue.service;

import com.squirrel.tickting.domain.venue.infra.entity.Venue;
import com.squirrel.tickting.domain.venue.infra.repository.VenueRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueSeatRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueZoneRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VenueService {

	private final VenueRepository venueRepository;
	private final VenueZoneRepository venueZoneRepository;
	private final VenueSeatRepository venueSeatRepository;

	/** 공연장 번호별 이름. 사용 중지된 공연장도 포함한다. (이미 잡힌 공연의 장소는 계속 보여야 한다) */
	@Transactional(readOnly = true)
	public Map<Long, String> getNames(Collection<Long> venueIds) {
		return venueRepository.findAllById(venueIds).stream()
			.collect(Collectors.toMap(Venue::getId, Venue::getName));
	}
}
