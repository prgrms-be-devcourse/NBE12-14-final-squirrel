package com.squirrel.tickting.domain.venue.infra.repository;

import com.squirrel.tickting.domain.venue.infra.entity.VenueZone;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenueZoneRepository extends JpaRepository<VenueZone, Long> {

	/** 공연장의 모든 구역 번호. 엔티티 전체가 아니라 번호만 쓰므로 번호만 읽는다. (idx_venue_zone_venue 를 탄다) */
	@Query("""
		SELECT z.id
		FROM VenueZone z
		WHERE z.venue.id = :venueId
		ORDER BY z.sortOrder, z.id
		""")
	List<Long> findIdsByVenueId(@Param("venueId") Long venueId);

	/** 공연장의 모든 구역. (idx_venue_zone_venue 를 탄다) */
	List<VenueZone> findByVenueId(Long venueId);
}
