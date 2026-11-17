package com.squirrel.tickting.domain.venue.infra.repository;

import com.squirrel.tickting.domain.venue.infra.entity.VenueSeat;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenueSeatRepository extends JpaRepository<VenueSeat, Long> {

	/** 번호로 좌석을 읽으면서 구역도 함께 읽는다. 좌석마다 구역을 다시 조회하지 않게 한다. */
	@Query("SELECT s FROM VenueSeat s JOIN FETCH s.zone WHERE s.id IN :ids")
	List<VenueSeat> findAllWithZone(@Param("ids") Collection<Long> ids);
}
