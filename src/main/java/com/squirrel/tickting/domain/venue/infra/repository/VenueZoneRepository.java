package com.squirrel.tickting.domain.venue.infra.repository;

import com.squirrel.tickting.domain.venue.infra.entity.VenueZone;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueZoneRepository extends JpaRepository<VenueZone, Long> {
}
