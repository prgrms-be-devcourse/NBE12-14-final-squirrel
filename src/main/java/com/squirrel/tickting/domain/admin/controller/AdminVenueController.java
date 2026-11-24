package com.squirrel.tickting.domain.admin.controller;

import com.squirrel.tickting.domain.venue.service.VenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/venues")
@RequiredArgsConstructor
public class AdminVenueController {

	private final VenueService venueService;
}
