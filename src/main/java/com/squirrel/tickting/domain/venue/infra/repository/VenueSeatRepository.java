package com.squirrel.tickting.domain.venue.infra.repository;

import com.squirrel.tickting.domain.venue.infra.entity.VenueSeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueSeatRepository extends JpaRepository<VenueSeat, Long> {
}
