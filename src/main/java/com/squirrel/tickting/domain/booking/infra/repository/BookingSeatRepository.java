package com.squirrel.tickting.domain.booking.infra.repository;

import com.squirrel.tickting.domain.booking.infra.entity.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {
}
