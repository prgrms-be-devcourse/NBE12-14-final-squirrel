package com.squirrel.tickting.domain.venue.infra.repository;

import com.squirrel.tickting.domain.venue.infra.entity.VenueRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VenueRequestRepository extends JpaRepository<VenueRequest, Long> {
}
