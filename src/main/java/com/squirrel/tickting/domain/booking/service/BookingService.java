package com.squirrel.tickting.domain.booking.service;

import com.squirrel.tickting.domain.booking.infra.repository.BookingRepository;
import com.squirrel.tickting.domain.booking.infra.repository.BookingSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookingService {

	private final BookingRepository bookingRepository;
	private final BookingSeatRepository bookingSeatRepository;
}
