package com.squirrel.tickting.domain.venue.service;

import com.squirrel.tickting.domain.venue.infra.repository.VenueRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VenueRequestService {

	private final VenueRequestRepository venueRequestRepository;
}
