package com.squirrel.tickting.domain.performance.infra.repository;

import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceImageRepository extends JpaRepository<PerformanceImage, Long> {

	/** 공연의 상세 이미지. 보여줄 순서(sortOrder)대로다. */
	List<PerformanceImage> findByPerformanceIdOrderBySortOrder(Long performanceId);
}
