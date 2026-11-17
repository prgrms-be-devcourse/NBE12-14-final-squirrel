package com.squirrel.tickting.domain.venue.infra.repository;

import com.squirrel.tickting.domain.venue.infra.entity.VenueSeat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenueSeatRepository extends JpaRepository<VenueSeat, Long> {

	/** 공연장의 구역별 좌석 수. 구역마다 따로 세지 않고 한 번에 센다. 좌석이 없는 구역은 결과에 나오지 않는다. (idx_venue_seat_venue 를 탄다) */
	@Query("""
		SELECT s.zone.id AS zoneId, COUNT(s) AS seatCount
		FROM VenueSeat s
		WHERE s.venue.id = :venueId
		GROUP BY s.zone.id
		""")
	List<ZoneSeatCount> countByZone(@Param("venueId") Long venueId);

	interface ZoneSeatCount {

		Long getZoneId();

		long getSeatCount();
	}
}
