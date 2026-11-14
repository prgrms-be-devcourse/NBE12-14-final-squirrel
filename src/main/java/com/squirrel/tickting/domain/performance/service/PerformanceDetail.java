package com.squirrel.tickting.domain.performance.service;

import com.squirrel.tickting.domain.performance.infra.entity.Performance;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceImage;
import com.squirrel.tickting.domain.performance.infra.entity.PerformanceSchedule;
import java.util.List;

/**
 * 공연 상세 조회 결과. 공연과 이미지·회차를 서비스 트랜잭션 안에서 함께 읽어 담는다.
 * (컨트롤러에서 지연 로딩 객체를 꺼내면 트랜잭션이 끝난 뒤라 LazyInitializationException 이 난다)
 */
public record PerformanceDetail(
	Performance performance,
	List<PerformanceImage> images,
	List<PerformanceSchedule> schedules
) {
}
