package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PerformanceImageRepository extends JpaRepository<PerformanceImage, Long> {

	/** 공연의 상세 이미지. 보여줄 순서(sortOrder)대로다. */
	List<PerformanceImage> findByPerformanceIdOrderBySortOrder(Long performanceId);

	/**
	 * 공연의 상세 이미지를 모두 지운다. 공연 수정은 이미지를 전체 교체해서 지우고 다시 저장한다.
	 * 파생 삭제 메서드는 저장보다 늦게 실행될 수 있어 쿼리로 바로 지운다. 영속성 컨텍스트는 비우지 않는다. (같은 트랜잭션의 공연 엔티티가 사라지지 않게)
	 */
	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM PerformanceImage i WHERE i.performance.id = :performanceId")
	void deleteByPerformanceId(@Param("performanceId") Long performanceId);
}
