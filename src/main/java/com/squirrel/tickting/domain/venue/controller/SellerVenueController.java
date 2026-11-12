package com.squirrel.tickting.domain.venue.controller;

import com.squirrel.tickting.domain.venue.service.VenueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller/venues")
@RequiredArgsConstructor
public class SellerVenueController {

	private final VenueService venueService;
}
