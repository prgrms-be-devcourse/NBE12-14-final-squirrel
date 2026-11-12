package com.squirrel.tickting.domain.venue.service;

import com.squirrel.tickting.domain.venue.infra.repository.VenueRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueSeatRepository;
import com.squirrel.tickting.domain.venue.infra.repository.VenueZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VenueService {

	private final VenueRepository venueRepository;
	private final VenueZoneRepository venueZoneRepository;
	private final VenueSeatRepository venueSeatRepository;
}
