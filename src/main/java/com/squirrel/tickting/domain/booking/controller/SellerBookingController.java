package com.squirrel.tickting.domain.booking.controller;

import com.squirrel.tickting.domain.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/seller")
@RequiredArgsConstructor
public class SellerBookingController {

	private final BookingService bookingService;
}
